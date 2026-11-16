package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Explicit allowlist of notification channels per order lifecycle event and the
 * fresh email eligibility gate. Decisions use immutable event facts plus a
 * freshly read order state; nothing here confirms money, stock, or shipment.
 */
@Component
public final class OrderNotificationPolicy {

    /** The only channels this module may use for an order lifecycle event. */
    public record ChannelDecisions(
            boolean customerInApp,
            boolean customerEmail,
            boolean companyInApp,
            boolean companyEmail) {
    }

    /** Fresh order/payment state read immediately before an email attempt. */
    public record CurrentOrderState(
            OrderStatus orderStatus,
            PaymentStatus paymentStatus,
            Instant paymentExpiresAt) {
    }

    public enum EmailEligibility { ELIGIBLE, SKIPPED }

    /** Backend-owned decision per event; no browser or event-supplied audience is accepted. */
    public ChannelDecisions channels(OrderEvent event) {
        OrderEventType type = event.eventType();
        boolean customerEmail = switch (type) {
            // No v1 email for a refund review or an admin-completed order.
            case REFUND_REQUIRED, ORDER_COMPLETED -> false;
            default -> true;
        };
        boolean companyInApp = switch (type) {
            // Company learns of a new order only when approval/payment is pending.
            case ORDER_PLACED -> event.paymentMethod() == PaymentMethod.COD;
            default -> true;
        };
        // Company email is never sent, for any event.
        return new ChannelDecisions(true, customerEmail, companyInApp, false);
    }

    /**
     * Pure decision from immutable event facts plus a freshly read state and a
     * fresh clock. Never writes order, payment, stock, or any ledger row.
     */
    public EmailEligibility emailEligibility(
            OrderEvent event, CurrentOrderState current,
            boolean deadlineActive, Instant databaseNow) {
        if (!channels(event).customerEmail()) {
            return EmailEligibility.SKIPPED;
        }
        return switch (event.eventType()) {
            case ORDER_PLACED -> placedReceiptEligibility(
                    event.paymentMethod(), current, deadlineActive, databaseNow);
            // A queued confirmation must not go out for an order cancelled meanwhile.
            // Cancellation notices themselves describe that cancellation and still send.
            case ORDER_CONFIRMED -> current.orderStatus() == OrderStatus.CANCELLED
                    ? EmailEligibility.SKIPPED : EmailEligibility.ELIGIBLE;
            default -> EmailEligibility.ELIGIBLE;
        };
    }

    private static EmailEligibility placedReceiptEligibility(
            PaymentMethod method, CurrentOrderState current,
            boolean deadlineActive, Instant databaseNow) {
        if (current.orderStatus() != OrderStatus.PENDING
                || current.paymentStatus() != PaymentStatus.PENDING) {
            return EmailEligibility.SKIPPED;
        }
        // COD is exempt from the deadline predicate, not from the pending checks.
        if (method == PaymentMethod.COD) {
            return EmailEligibility.ELIGIBLE;
        }
        // A null or inactive online deadline quarantines the receipt; it is never
        // treated as an unlimited payment window. Equality is already too late.
        if (!deadlineActive || current.paymentExpiresAt() == null
                || !databaseNow.isBefore(current.paymentExpiresAt())) {
            return EmailEligibility.SKIPPED;
        }
        return EmailEligibility.ELIGIBLE;
    }
}
