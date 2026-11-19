package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.event.OrderEventType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;

/**
 * Renders truthful, escaped copy from immutable event facts. Copy is derived
 * from the event's own status/reason/method/total, never from a later order
 * snapshot, so a COD order is never called paid and an admin completion is
 * never called shipped or delivered.
 */
@Component
public final class NotificationMessageRenderer {

    /** Facts frozen from the event at handling time; never a later order snapshot. */
    public record MessageFacts(
            OrderEventType eventType,
            OrderStatus orderStatus,
            PaymentStatus paymentStatus,
            PaymentMethod paymentMethod,
            OrderEventReason reason,
            String total,
            String orderId,
            Instant occurredAt) {
    }

    public MessageFacts factsOf(OrderEvent event) {
        return new MessageFacts(
                event.eventType(),
                event.orderStatus(),
                event.paymentStatus(),
                event.paymentMethod(),
                event.reason(),
                event.total(),
                event.orderId(),
                event.occurredAt());
    }

    public String customerMessage(MessageFacts facts) {
        String order = escape(facts.orderId());
        String total = escape(facts.total());
        return switch (facts.eventType()) {
            case ORDER_PLACED -> facts.paymentMethod() == PaymentMethod.COD
                    ? "Order " + order + " received. Your order is pending approval; "
                            + "payment is due on delivery. Total: " + total + "."
                    : "Order " + order + " received. Payment is pending. Total: " + total + ".";
            case ORDER_CONFIRMED -> facts.paymentStatus() == PaymentStatus.CONFIRMED
                    ? "Order " + order + " confirmed. Payment received. Total: " + total + "."
                    : "Order " + order + " confirmed. Payment is due on delivery. "
                            + "Total: " + total + ".";
            case ORDER_REJECTED -> "Order " + order + " was rejected" + reasonSuffix(facts.reason()) + ".";
            case ORDER_EXPIRED -> "Order " + order + " was cancelled because the payment window expired.";
            case PAYMENT_FAILED -> "Order " + order + " was cancelled because payment failed.";
            case REFUND_REQUIRED -> "Order " + order
                    + ": a refund is under review. We will contact you when the review completes.";
            case ORDER_COMPLETED -> "Order " + order + " was completed. Thank you for shopping with us.";
            case ORDER_SHIPPED -> "Order " + order + " shipment confirmed.";
            case ORDER_REFUNDED -> "Order " + order + " refund confirmed.";
        };
    }

    public String companyMessage(MessageFacts facts) {
        String order = escape(facts.orderId());
        return switch (facts.eventType()) {
            case ORDER_PLACED -> facts.paymentMethod() == PaymentMethod.COD
                    ? "New COD order " + order + " awaiting approval. Total: "
                            + escape(facts.total()) + "."
                    : "New online order " + order + " awaiting payment. Total: "
                            + escape(facts.total()) + ".";
            case ORDER_CONFIRMED -> "Order " + order + " is ready for processing.";
            case REFUND_REQUIRED -> "Order " + order + " requires action: refund review requested.";
            case ORDER_REJECTED -> "Order " + order + " status update: rejected.";
            case ORDER_EXPIRED -> "Order " + order + " status update: cancelled, payment window expired.";
            case PAYMENT_FAILED -> "Order " + order + " status update: cancelled, payment failed.";
            case ORDER_COMPLETED -> "Order " + order + " status update: completed.";
            case ORDER_SHIPPED -> "Order " + order + " status update: shipped.";
            case ORDER_REFUNDED -> "Order " + order + " status update: refunded.";
        };
    }

    private static String reasonSuffix(OrderEventReason reason) {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case STAFF_REJECTED -> " by staff";
            case PAYMENT_EXPIRED -> " after the payment window expired";
            case PAYMENT_FAILED -> " after payment failed";
        };
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
