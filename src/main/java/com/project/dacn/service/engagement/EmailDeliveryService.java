package com.project.dacn.service.engagement;

import com.project.dacn.entity.engagement.EmailDeliveryState;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.repository.engagement.EmailDeliveryRepository;
import com.project.dacn.repository.engagement.EmailDeliveryRepository.ClaimedRow;
import com.project.dacn.repository.engagement.EmailDeliveryRepository.DeliveryRow;
import com.project.dacn.repository.engagement.EmailDeliveryRepository.FreshOrderState;
import com.project.dacn.service.engagement.OrderNotificationPolicy.CurrentOrderState;
import com.project.dacn.service.engagement.OrderNotificationPolicy.EmailEligibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Short, fenced ledger transactions for email delivery. A claim commits before
 * any network work; the returned immutable Claim is the only proof of ownership
 * accepted by the finalization methods. No JPA entity, socket or SMTP code lives
 * here or crosses into the transport.
 */
@Service
public class EmailDeliveryService {

    public static final Duration LEASE = Duration.ofSeconds(60);
    public static final int MAX_ATTEMPTS = 3;

    // Same bounded schedule as the foundation's notification retry queues.
    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(120));

    public record Claim(long deliveryId, UUID eventId, long recipientUserId,
                        UUID claimToken, Instant leaseExpiresAt, int attempt,
                        long commitAnchor) {
    }

    public sealed interface ClaimResult permits Claimed, Completed, Busy, ReviewRequired {
    }

    public record Claimed(Claim claim) implements ClaimResult {
    }

    public enum Completion { ALREADY_SENT, ALREADY_SKIPPED, POLICY_SKIPPED, NO_EMAIL_ROW }

    public record Completed(Completion reason, EmailDeliveryState state) implements ClaimResult {
    }

    public enum BusyReason { ACTIVE_CLAIM, RETRY_NOT_DUE }

    public record Busy(BusyReason reason, long deliveryId, Instant nextEligibleAt)
            implements ClaimResult {
    }

    public record ReviewRequired(EmailDeliveryState state, long deliveryId, String failureCode)
            implements ClaimResult {
    }

    private final EmailDeliveryRepository repository;
    private final OrderNotificationPolicy policy;
    private final TransactionTemplate transaction;

    public EmailDeliveryService(EmailDeliveryRepository repository,
                                OrderNotificationPolicy policy,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.policy = policy;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Claims an eligible row or reports why no network work may start. */
    public ClaimResult claim(OrderEvent event, long recipientUserId) {
        return transaction.execute(status -> claimInTransaction(event, recipientUserId));
    }

    public boolean completeSent(Claim claim) {
        return finishTerminal(claim, EmailDeliveryState.SENT, null);
    }

    public boolean completeSkipped(Claim claim) {
        return finishTerminal(claim, EmailDeliveryState.SKIPPED, null);
    }

    /**
     * Proven pre-acceptance failure: bounded retry while attempts remain, then
     * retained FAILED/DLQ review. Attempt count never resets.
     */
    public boolean recordTransientFailure(Claim claim, String failureCode) {
        String code = safeCode(failureCode);
        if (claim.attempt() >= MAX_ATTEMPTS) {
            return finishTerminal(claim, EmailDeliveryState.FAILED, code);
        }
        Duration delay = RETRY_DELAYS.get(Math.max(0, claim.attempt() - 1));
        return Boolean.TRUE.equals(transaction.execute(status -> repository.finishRetry(
                claim.deliveryId(), claim.claimToken(), claim.attempt(),
                claim.leaseExpiresAt(), delay, code)));
    }

    /** Acceptance is possible but unobserved: quarantine, never an automatic resend. */
    public boolean recordUnknown(Claim claim, String failureCode) {
        return finishTerminal(claim, EmailDeliveryState.UNKNOWN, safeCode(failureCode));
    }

    public boolean recordPermanentFailure(Claim claim, String failureCode) {
        return finishTerminal(claim, EmailDeliveryState.FAILED, safeCode(failureCode));
    }

    private boolean finishTerminal(Claim claim, EmailDeliveryState state, String failureCode) {
        return Boolean.TRUE.equals(transaction.execute(status -> repository.finishTerminal(
                claim.deliveryId(), claim.claimToken(), claim.attempt(),
                claim.leaseExpiresAt(), state.name(), failureCode)));
    }

    private ClaimResult claimInTransaction(OrderEvent event, long recipientUserId) {
        DeliveryRow row = repository.findForUpdate(event.eventId(), recipientUserId);
        if (row != null) {
            return existing(row, event, recipientUserId);
        }
        // Unsupported email channels are acknowledged with no pointless ledger row.
        if (!policy.channels(event).customerEmail()) {
            return new Completed(Completion.NO_EMAIL_ROW, null);
        }

        FreshOrderState fresh = repository.freshOrderState(event.orderId());
        EmailEligibility eligibility = EmailEligibility.ELIGIBLE;
        if (fresh == null) {
            eligibility = EmailEligibility.SKIPPED;
        } else {
            eligibility = policy.emailEligibility(event,
                    new CurrentOrderState(fresh.orderStatus(), fresh.paymentStatus(),
                            fresh.paymentExpiresAt()),
                    fresh.deadlineActive(), fresh.databaseNow());
        }
        if (eligibility == EmailEligibility.SKIPPED) {
            Long skippedId = repository.insertSkipped(event.eventId(), recipientUserId);
            if (skippedId == null) {
                DeliveryRow winner = repository.findForUpdate(event.eventId(), recipientUserId);
                if (winner == null) {
                    throw new IllegalStateException("Email delivery row vanished after skip conflict");
                }
                return existing(winner, event, recipientUserId);
            }
            return new Completed(Completion.POLICY_SKIPPED, EmailDeliveryState.SKIPPED);
        }

        Long id = repository.insertPending(event.eventId(), recipientUserId);
        if (id == null) {
            DeliveryRow winner = repository.findForUpdate(event.eventId(), recipientUserId);
            if (winner == null) {
                throw new IllegalStateException("Email delivery row vanished after insert conflict");
            }
            return existing(winner, event, recipientUserId);
        }
        return claimRow(id, event, recipientUserId);
    }

    private ClaimResult existing(DeliveryRow row, OrderEvent event, long recipientUserId) {
        return switch (row.state()) {
            case "SENT" -> new Completed(Completion.ALREADY_SENT, EmailDeliveryState.SENT);
            case "SKIPPED" -> new Completed(Completion.ALREADY_SKIPPED, EmailDeliveryState.SKIPPED);
            case "UNKNOWN" -> new ReviewRequired(EmailDeliveryState.UNKNOWN,
                    row.id(), row.failureCode());
            case "FAILED" -> new ReviewRequired(EmailDeliveryState.FAILED,
                    row.id(), row.failureCode());
            case "IN_FLIGHT" -> {
                if (!repository.isLeaseExpired(row.id())) {
                    yield new Busy(BusyReason.ACTIVE_CLAIM, row.id(), row.claimExpiresAt());
                }
                // Lease expiry alone is not proof of failure. Retain UNKNOWN evidence.
                repository.quarantineExpired(row.id());
                yield new ReviewRequired(EmailDeliveryState.UNKNOWN,
                        row.id(), "LEASE_EXPIRED_UNKNOWN");
            }
            case "PENDING" -> repository.isRetryDue(row.id())
                    ? claimRow(row.id(), event, recipientUserId)
                    : new Busy(BusyReason.RETRY_NOT_DUE, row.id(), row.nextEligibleAt());
            default -> throw new IllegalStateException("Unexpected email delivery state: " + row.state());
        };
    }

    private ClaimResult claimRow(long id, OrderEvent event, long recipientUserId) {
        UUID token = UUID.randomUUID();
        ClaimedRow claimed = repository.claimPending(id, token, LEASE);
        if (claimed == null) {
            throw new IllegalStateException("Eligible email delivery row could not be claimed: " + id);
        }
        return new Claimed(new Claim(id, event.eventId(), recipientUserId, token,
                claimed.claimExpiresAt(), claimed.attemptCount(), claimed.commitAnchor()));
    }

    private static String safeCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return "UNSPECIFIED";
        }
        String code = failureCode.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
        return code.length() > 64 ? code.substring(0, 64) : code;
    }
}
