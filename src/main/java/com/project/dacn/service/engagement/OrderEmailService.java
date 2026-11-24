package com.project.dacn.service.engagement;

import com.project.dacn.event.OrderEvent;
import com.project.dacn.integration.mail.MailContentProperties;
import com.project.dacn.integration.mail.SmtpAttempt;
import com.project.dacn.integration.mail.SmtpAttemptExecutor;
import com.project.dacn.integration.mail.SmtpAttemptResult;
import com.project.dacn.repository.engagement.MailEligibilityRepository;
import com.project.dacn.repository.engagement.MailEligibilityRepository.FreshSendFacts;
import com.project.dacn.service.engagement.EmailDeliveryService.Claim;
import com.project.dacn.service.engagement.EmailDeliveryService.Claimed;
import com.project.dacn.service.engagement.EmailDeliveryService.ClaimResult;
import com.project.dacn.service.engagement.OrderNotificationPolicy.CurrentOrderState;
import com.project.dacn.service.engagement.OrderNotificationPolicy.EmailEligibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Orchestrates one email delivery attempt per call. It only claims the ledger,
 * takes one fresh eligibility read, renders from immutable event facts, runs one
 * transport attempt and records the outcome. No broker, consumer, template
 * admin, publishing, refund/shipping simulation or delivery enablement lives
 * here.
 *
 * <p>Contract for the Task 11 consumer adapter: process is void and durable.
 * The ledger row is the authority. After return, the adapter acknowledges only
 * when the event's in-app work also committed; a transient ledger failure
 * leaves a PENDING row with a bounded next-eligible time for the broker's own
 * retry. A terminal SENT/SKIPPED/UNKNOWN/FAILED row is never re-sent by this
 * service, so redelivery cannot start a second SMTP attempt.</p>
 */
@Service
public class OrderEmailService {

    private static final Logger log = LoggerFactory.getLogger(OrderEmailService.class);

    public enum FreshKind { SEND, SKIP, QUARANTINE }

    public record FreshOutcome(FreshKind kind, String code, String recipientEmail) {
    }

    private static final long EVENT_WINDOW_DAYS = 30;

    private final EmailDeliveryService delivery;
    private final MailEligibilityRepository eligibility;
    private final OrderNotificationPolicy policy;
    private final SmtpAttemptExecutor smtp;
    private final MailViewBuilder views;
    private final TransactionTemplate transaction;

    public OrderEmailService(EmailDeliveryService delivery,
                             MailEligibilityRepository eligibility,
                             OrderNotificationPolicy policy,
                             SmtpAttemptExecutor smtp,
                             MailViewBuilder views,
                             MailContentProperties properties,
                             PlatformTransactionManager transactionManager) {
        this.delivery = delivery;
        this.eligibility = eligibility;
        this.policy = policy;
        this.smtp = smtp;
        this.views = views;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void process(OrderEvent event) {
        long recipientId;
        try {
            recipientId = Long.parseLong(event.customerUserId());
        } catch (NumberFormatException e) {
            log.warn("email event rejected code=EVENT_RECIPIENT_INVALID eventId={}", event.eventId());
            return;
        }

        ClaimResult claimResult = delivery.claim(event, recipientId);
        if (!(claimResult instanceof Claimed claimed)) {
            // Completed/busy/review-required results are ledger outcomes already; no SMTP.
            return;
        }
        Claim claim = claimed.claim();

        // The only fresh, authoritative eligibility read. It runs in one short
        // transaction that is committed before any transport work starts.
        FreshOutcome fresh = transaction.execute(status -> freshCheck(claim, event, recipientId));
        if (fresh == null) {
            return;
        }
        switch (fresh.kind()) {
            case SKIP -> {
                delivery.completeSkipped(claim);
                log.info("email outcome eventId={} deliveryId={} outcome=SKIPPED code={}",
                        event.eventId(), claim.deliveryId(), fresh.code());
            }
            case QUARANTINE -> {
                delivery.recordUnknown(claim, fresh.code());
                log.info("email outcome eventId={} deliveryId={} outcome=UNKNOWN code={}",
                        event.eventId(), claim.deliveryId(), fresh.code());
            }
            case SEND -> send(claim, event, fresh.recipientEmail());
        }
    }

    private FreshOutcome freshCheck(Claim claim, OrderEvent event, long recipientId) {
        FreshSendFacts facts = eligibility.fresh(claim.eventId(), claim.deliveryId(),
                claim.claimToken(), claim.attempt(), recipientId, event.orderId());
        Instant dbNow = facts.databaseNow();

        if (!facts.ownershipValid()) {
            return new FreshOutcome(FreshKind.QUARANTINE, "STALE_OWNERSHIP", null);
        }
        if (dbNow == null || claim.leaseExpiresAt() == null
                || !claim.leaseExpiresAt().isAfter(dbNow.plus(SmtpAttempt.ATTEMPT_DEADLINE))) {
            return new FreshOutcome(FreshKind.QUARANTINE, "LEASE_TIME_INSUFFICIENT", null);
        }
        if (System.nanoTime() - claim.claimedAtNanos() >= SmtpAttempt.ATTEMPT_DEADLINE.toNanos()) {
            return new FreshOutcome(FreshKind.QUARANTINE, "CLAIM_BUDGET_ELAPSED", null);
        }

        Instant occurred = event.occurredAt();
        if (occurred.isAfter(dbNow)
                || !dbNow.isBefore(occurred.plus(EVENT_WINDOW_DAYS, ChronoUnit.DAYS))) {
            return new FreshOutcome(FreshKind.QUARANTINE, "EVENT_WINDOW_INVALID", null);
        }

        if (!facts.recipientActive() || facts.recipientEmail() == null
                || facts.recipientEmail().isBlank()) {
            return new FreshOutcome(FreshKind.SKIP, "RECIPIENT_INELIGIBLE", null);
        }
        if (facts.orderStatus() == null || facts.paymentStatus() == null) {
            return new FreshOutcome(FreshKind.SKIP, "ORDER_STATE_MISSING", null);
        }

        EmailEligibility decision = policy.emailEligibility(event,
                new CurrentOrderState(facts.orderStatus(), facts.paymentStatus(),
                        facts.paymentExpiresAt()),
                facts.deadlineActive(), dbNow);
        if (decision == EmailEligibility.SKIPPED) {
            return new FreshOutcome(FreshKind.SKIP, "POLICY_SKIPPED", null);
        }
        return new FreshOutcome(FreshKind.SEND, null, facts.recipientEmail());
    }

    private void send(Claim claim, OrderEvent event, String recipientEmail) {
        SmtpAttemptResult result = smtp.execute(claim,
                views.factory(event, claim.deliveryId(), claim.attempt(), recipientEmail));
        switch (result.outcome()) {
            case ACCEPTED -> {
                delivery.completeSent(claim);
                log.info("email outcome eventId={} deliveryId={} outcome=SENT code=SMTP_ACCEPTED",
                        event.eventId(), claim.deliveryId());
            }
            case PRE_ACCEPTANCE_FAILURE -> {
                // Proven no acceptance: bounded retry, then retained FAILED/DLQ.
                delivery.recordTransientFailure(claim, result.failureCode());
                log.info("email outcome eventId={} deliveryId={} outcome=RETRY code={}",
                        event.eventId(), claim.deliveryId(), result.failureCode());
            }
            case UNKNOWN -> {
                delivery.recordUnknown(claim, result.failureCode());
                log.info("email outcome eventId={} deliveryId={} outcome=UNKNOWN code={}",
                        event.eventId(), claim.deliveryId(), result.failureCode());
            }
            // A future outcome must quarantine, never leave the claim IN_FLIGHT.
            default -> delivery.recordUnknown(claim, "UNKNOWN_OUTCOME");
        }
    }
}
