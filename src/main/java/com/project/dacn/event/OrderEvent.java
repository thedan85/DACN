package com.project.dacn.event;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public record OrderEvent(
        int schemaVersion,
        UUID eventId,
        OrderEventType eventType,
        String orderId,
        long orderSequence,
        Instant occurredAt,
        String customerUserId,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        PaymentMethod paymentMethod,
        String total,
        OrderEventReason reason,
        String correlationId,
        String entryPoint) {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Pattern CUSTOMER_USER_ID = Pattern.compile("[1-9]\\d*");
    private static final Pattern TOTAL = Pattern.compile("^\\d{1,8}\\.\\d{2}$");
    private static final Pattern ENTRY_POINT = Pattern.compile("[A-Z0-9_.-]+");
    private static final int MAX_CORRELATION_ID_LENGTH = 128;
    private static final int MAX_ENTRY_POINT_LENGTH = 32;

    public OrderEvent {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION, "schemaVersion");
        require(eventId != null, "eventId");
        require(eventType != null, "eventType");
        require(orderId != null && !orderId.isBlank(), "orderId");
        require(orderSequence > 0, "orderSequence");
        require(occurredAt != null, "occurredAt");
        require(customerUserId != null && CUSTOMER_USER_ID.matcher(customerUserId).matches(),
                "customerUserId");
        require(orderStatus != null, "orderStatus");
        require(paymentStatus != null, "paymentStatus");
        require(paymentMethod != null, "paymentMethod");
        require(total != null && TOTAL.matcher(total).matches(), "total");
        require(reason == reasonFor(eventType), "reason");
        require(correlationId != null && !correlationId.isBlank()
                && correlationId.length() <= MAX_CORRELATION_ID_LENGTH, "correlationId");
        require(entryPoint != null && entryPoint.length() <= MAX_ENTRY_POINT_LENGTH
                && ENTRY_POINT.matcher(entryPoint).matches(), "entryPoint");
    }

    public static OrderEvent create(
            OrderEventType eventType,
            String orderId,
            long orderSequence,
            Instant occurredAt,
            long customerUserId,
            OrderStatus orderStatus,
            PaymentStatus paymentStatus,
            PaymentMethod paymentMethod,
            BigDecimal total,
            OrderEventReason reason,
            String correlationId,
            String entryPoint) {
        String totalText;
        try {
            totalText = total == null ? null
                    : total.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new OrderEventValidationException("total");
        }
        return new OrderEvent(SUPPORTED_SCHEMA_VERSION, UUID.randomUUID(), eventType, orderId,
                orderSequence, occurredAt, Long.toString(customerUserId), orderStatus,
                paymentStatus, paymentMethod, totalText, reason, correlationId, entryPoint);
    }

    private static OrderEventReason reasonFor(OrderEventType eventType) {
        return switch (eventType) {
            case ORDER_REJECTED -> OrderEventReason.STAFF_REJECTED;
            case ORDER_EXPIRED -> OrderEventReason.PAYMENT_EXPIRED;
            case PAYMENT_FAILED -> OrderEventReason.PAYMENT_FAILED;
            default -> null;
        };
    }

    private static void require(boolean condition, String field) {
        if (!condition) {
            throw new OrderEventValidationException(field);
        }
    }
}
