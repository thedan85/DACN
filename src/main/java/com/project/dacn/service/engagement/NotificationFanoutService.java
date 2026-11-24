package com.project.dacn.service.engagement;

import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.entity.engagement.NotificationRecipientScope;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard.AgeDecision;
import com.project.dacn.repository.engagement.NotificationFanoutRepository;
import com.project.dacn.repository.engagement.NotificationFanoutRepository.NotificationInsert;
import com.project.dacn.repository.engagement.NotificationFanoutRepository.ReceiptState;
import com.project.dacn.repository.engagement.NotificationRecipientQueries;
import com.project.dacn.repository.engagement.NotificationRecipientQueries.CompanyRecipient;
import com.project.dacn.service.engagement.NotificationMessageRenderer.MessageFacts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Consumes a committed, in-window order event into in-app inbox rows in one
 * short transaction. In-app idempotency is the receipt event uniqueness plus
 * the notification (source_event_id, user_id) uniqueness - nothing else. No
 * INAPP delivery-ledger row is ever created here.
 */
@Service
public class NotificationFanoutService {

    private static final String MISSING_EVIDENCE = "MISSING_COMPLETED_EVIDENCE";
    private static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";

    private final NotificationFanoutRepository repository;
    private final NotificationRecipientQueries recipientQueries;
    private final DatabaseClock databaseClock;
    private final OrderEventAgeGuard ageGuard;
    private final NotificationMessageRenderer renderer;
    private final OrderNotificationPolicy policy;

    public NotificationFanoutService(
            NotificationFanoutRepository repository,
            NotificationRecipientQueries recipientQueries,
            DatabaseClock databaseClock,
            OrderEventAgeGuard ageGuard,
            NotificationMessageRenderer renderer,
            OrderNotificationPolicy policy) {
        this.repository = repository;
        this.recipientQueries = recipientQueries;
        this.databaseClock = databaseClock;
        this.ageGuard = ageGuard;
        this.renderer = renderer;
        this.policy = policy;
    }

    @Transactional
    public void handle(OrderEvent event) {
        Objects.requireNonNull(event, "event");

        // Foundation's age guard decides admission with a fresh database clock.
        AgeDecision initialDecision = ageGuard.evaluate(event);
        if (initialDecision != AgeDecision.OK) {
            repository.quarantineNew(event.eventId(), failureCode(initialDecision));
            return;
        }

        long internalCustomerId;
        try {
            internalCustomerId = Long.parseLong(event.customerUserId());
        } catch (NumberFormatException exception) {
            repository.quarantineNew(event.eventId(), "EVENT_MALFORMED");
            return;
        }

        Long claimId = repository.claimReceipt(event.eventId());
        if (claimId == null) {
            // A retained receipt is the only dedup evidence. An unfinished claim
            // is anomalous: quarantine it rather than resolving a fresh audience.
            if (repository.receiptState(event.eventId()) == ReceiptState.CLAIMED_INCOMPLETE) {
                repository.quarantine(event.eventId(), MISSING_EVIDENCE);
            }
            return;
        }

        List<Long> customerIds = recipientQueries.customerRecipientByInternalId(internalCustomerId)
                .map(recipient -> List.of(recipient.userId()))
                .orElseGet(List::of);
        List<Long> companyIds = policy.channels(event).companyInApp()
                ? recipientQueries.companyRecipients().stream()
                        .map(CompanyRecipient::userId).toList()
                : List.of();
        List<Long> recipients = Stream.concat(customerIds.stream(), companyIds.stream())
                .distinct().sorted().toList();

        // Resolve, then lock in ascending ID order so fan-outs serialize the
        // same way as read/delete mutations.
        List<Long> locked = repository.lockRecipientsAscending(recipients);

        // Lock waits may have taken long enough to push the event out of the
        // processing window; recheck before any effect, with no new audience.
        AgeDecision recheck = ageGuard.evaluate(event, databaseClock.now());
        if (recheck != AgeDecision.OK) {
            repository.quarantine(event.eventId(), failureCode(recheck));
            return;
        }

        Long orderId = repository.resolveOrderSurrogateId(event.orderId());
        if (orderId == null) {
            repository.quarantine(event.eventId(), ORDER_NOT_FOUND);
            return;
        }

        MessageFacts facts = renderer.factsOf(event);
        String customerMessage = renderer.customerMessage(facts);
        String companyMessage = renderer.companyMessage(facts);
        for (Long userId : locked) {
            NotificationRecipientScope scope = customerIds.contains(userId)
                    ? NotificationRecipientScope.CUSTOMER
                    : NotificationRecipientScope.COMPANY;
            String message = scope == NotificationRecipientScope.CUSTOMER
                    ? customerMessage : companyMessage;
            boolean inserted = repository.insertNotificationIfAbsent(new NotificationInsert(
                    userId, notificationId(event, userId), message, event.eventId(), orderId,
                    event.eventType().name(), event.orderSequence(), event.occurredAt(), scope));
            if (inserted) {
                repository.incrementInboxRevision(userId);
            }
        }

        repository.completeReceipt(event.eventId(), locked);
    }

    private static String notificationId(OrderEvent event, long userId) {
        return "NOTI_" + UUID.randomUUID() + "_" + userId;
    }

    private static String failureCode(AgeDecision decision) {
        return switch (decision) {
            case TOO_OLD -> "EVENT_TOO_OLD";
            case FUTURE -> "EVENT_FUTURE";
            case OK -> throw new IllegalArgumentException("OK is not a quarantine decision");
        };
    }
}
