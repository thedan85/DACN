package com.project.dacn.event;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-03T10:15:30Z");

    @Test
    void createSetsSchemaVersionAndGeneratesOneEventId() {
        OrderEvent event = create(new BigDecimal("199.90"));

        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventId().version()).isEqualTo(4);
        assertThat(create(new BigDecimal("1.00")).eventId()).isNotEqualTo(create(new BigDecimal("1.00")).eventId());
    }

    @Test
    void createKeepsTheContractFieldsInOrder() {
        OrderEvent event = create(new BigDecimal("199.90"));

        assertThat(event.eventType()).isEqualTo(OrderEventType.ORDER_PLACED);
        assertThat(event.orderId()).isEqualTo("ORD_123");
        assertThat(event.orderSequence()).isEqualTo(1L);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.customerUserId()).isEqualTo("42");
        assertThat(event.orderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(event.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(event.paymentMethod()).isEqualTo(PaymentMethod.COD);
        assertThat(event.total()).isEqualTo("199.90");
        assertThat(event.reason()).isNull();
        assertThat(event.correlationId()).isEqualTo("corr-123");
        assertThat(event.entryPoint()).isEqualTo("CHECKOUT_WEB");
    }

    @Test
    void routingKeyUsesTheLifecyclePrefix() {
        assertThat(OrderEventType.ORDER_PLACED.routingKey())
                .isEqualTo("order.lifecycle.order_placed");
        assertThat(OrderEventType.REFUND_REQUIRED.routingKey())
                .isEqualTo("order.lifecycle.refund_required");
        assertThat(OrderEventType.ORDER_REFUNDED.routingKey())
                .isEqualTo("order.lifecycle.order_refunded");
    }

    @Test
    void contractHasNoPiiOrSecretFields() {
        assertThat(Arrays.stream(OrderEvent.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("schemaVersion", "eventId", "eventType", "orderId", "orderSequence",
                        "occurredAt", "customerUserId", "orderStatus", "paymentStatus", "paymentMethod",
                        "total", "reason", "correlationId", "entryPoint");
    }

    @Test
    void totalIsConvertedToTwoDecimalPlaces() {
        assertThat(create(new BigDecimal("12.5")).total()).isEqualTo("12.50");
        assertThat(create(new BigDecimal("1000")).total()).isEqualTo("1000.00");
        assertThat(create(new BigDecimal("1E+3")).total()).isEqualTo("1000.00");
        assertThat(create(new BigDecimal("0.00")).total()).isEqualTo("0.00");
    }

    @Test
    void totalWithMoreThanTwoDecimalsIsAValidationFailure() {
        assertThatThrownBy(() -> create(new BigDecimal("12.345")))
                .isInstanceOf(OrderEventValidationException.class);
    }

    @Test
    void totalMustMatchTheBoundedDecimalPattern() {
        assertThatThrownBy(() -> create(new BigDecimal("123456789.00")))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> raw("123456789.00"))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> raw("12.3"))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> raw("12,30"))
                .isInstanceOf(OrderEventValidationException.class);
        assertThat(raw("12345678.00").total()).isEqualTo("12345678.00");
    }

    @Test
    void customerUserIdMustBeAPositiveDecimalString() {
        assertThat(createForCustomer(1L).customerUserId()).isEqualTo("1");
        assertThat(createForCustomer(987654L).customerUserId()).isEqualTo("987654");
        assertThatThrownBy(() -> createForCustomer(0L))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForCustomer(-1L))
                .isInstanceOf(OrderEventValidationException.class);

        for (String invalid : new String[] {null, "", " ", "0", "01", "-1", "+1", "1 ", "abc"}) {
            assertThatThrownBy(() -> rawForCustomer(invalid))
                    .as("customerUserId %s", invalid)
                    .isInstanceOf(OrderEventValidationException.class);
        }
    }

    @Test
    void orderSequenceMustBePositive() {
        assertThatThrownBy(() -> createForSequence(0L))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForSequence(-1L))
                .isInstanceOf(OrderEventValidationException.class);
        assertThat(createForSequence(2L).orderSequence()).isEqualTo(2L);
    }

    @Test
    void requiredFieldsRejectNullAndBlank() {
        assertThatThrownBy(() -> createForOrderId(null))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForOrderId(" "))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForOccurredAt(null))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForOrderStatus(null))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForPaymentStatus(null))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForPaymentMethod(null))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForType(null))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> new OrderEvent(1, null, OrderEventType.ORDER_PLACED, "ORD_123", 1L,
                OCCURRED_AT, "42", OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                "1.00", null, "corr-123", "CHECKOUT_WEB"))
                .isInstanceOf(OrderEventValidationException.class);
    }

    @Test
    void schemaVersionMustBeExactlyOne() {
        assertThatThrownBy(() -> new OrderEvent(2, UUID.randomUUID(), OrderEventType.ORDER_PLACED,
                "ORD_123", 1L, OCCURRED_AT, "42", OrderStatus.PENDING, PaymentStatus.PENDING,
                PaymentMethod.COD, "1.00", null, "corr-123", "CHECKOUT_WEB"))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> new OrderEvent(0, UUID.randomUUID(), OrderEventType.ORDER_PLACED,
                "ORD_123", 1L, OCCURRED_AT, "42", OrderStatus.PENDING, PaymentStatus.PENDING,
                PaymentMethod.COD, "1.00", null, "corr-123", "CHECKOUT_WEB"))
                .isInstanceOf(OrderEventValidationException.class);
    }

    @Test
    void reasonIsNullExceptForItsFixedEventType() {
        assertThat(OrderEventReason.values()).containsExactly(OrderEventReason.STAFF_REJECTED,
                OrderEventReason.PAYMENT_EXPIRED, OrderEventReason.PAYMENT_FAILED);

        assertThat(createWithReason(OrderEventType.ORDER_REJECTED, OrderEventReason.STAFF_REJECTED).reason())
                .isEqualTo(OrderEventReason.STAFF_REJECTED);
        assertThat(createWithReason(OrderEventType.ORDER_EXPIRED, OrderEventReason.PAYMENT_EXPIRED).reason())
                .isEqualTo(OrderEventReason.PAYMENT_EXPIRED);
        assertThat(createWithReason(OrderEventType.PAYMENT_FAILED, OrderEventReason.PAYMENT_FAILED).reason())
                .isEqualTo(OrderEventReason.PAYMENT_FAILED);

        assertThatThrownBy(() -> createWithReason(OrderEventType.ORDER_REJECTED, null))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createWithReason(OrderEventType.ORDER_REJECTED, OrderEventReason.PAYMENT_FAILED))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createWithReason(OrderEventType.ORDER_EXPIRED, OrderEventReason.STAFF_REJECTED))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createWithReason(OrderEventType.PAYMENT_FAILED, OrderEventReason.PAYMENT_EXPIRED))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createWithReason(OrderEventType.ORDER_PLACED, OrderEventReason.STAFF_REJECTED))
                .isInstanceOf(OrderEventValidationException.class);
    }

    @Test
    void correlationIdIsNonBlankAndBounded() {
        assertThat(createForCorrelationId("a".repeat(128)).correlationId()).hasSize(128);
        assertThatThrownBy(() -> createForCorrelationId(null))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForCorrelationId(" "))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForCorrelationId("a".repeat(129)))
                .isInstanceOf(OrderEventValidationException.class);
    }

    @Test
    void entryPointIsBoundedAndUsesTheFixedAlphabet() {
        assertThat(createForEntryPoint("CHECKOUT_WEB.V2-1").entryPoint())
                .isEqualTo("CHECKOUT_WEB.V2-1");
        assertThat(createForEntryPoint("A".repeat(32)).entryPoint()).hasSize(32);

        assertThatThrownBy(() -> createForEntryPoint(null))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForEntryPoint(""))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForEntryPoint("checkout"))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForEntryPoint("CHECKOUT WEB"))
                .isInstanceOf(OrderEventValidationException.class);
        assertThatThrownBy(() -> createForEntryPoint("A".repeat(33)))
                .isInstanceOf(OrderEventValidationException.class);
    }

    private static OrderEvent create(BigDecimal total) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, OCCURRED_AT, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD, total, null,
                "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent raw(String total) {
        return new OrderEvent(1, UUID.randomUUID(), OrderEventType.ORDER_PLACED, "ORD_123", 1L,
                OCCURRED_AT, "42", OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                total, null, "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent rawForCustomer(String customerUserId) {
        return new OrderEvent(1, UUID.randomUUID(), OrderEventType.ORDER_PLACED, "ORD_123", 1L,
                OCCURRED_AT, customerUserId, OrderStatus.PENDING, PaymentStatus.PENDING,
                PaymentMethod.COD, "1.00", null, "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent createForCustomer(long customerUserId) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, OCCURRED_AT,
                customerUserId, OrderStatus.PENDING, PaymentStatus.PENDING,
                PaymentMethod.COD, new BigDecimal("1.00"), null, "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent createForSequence(long orderSequence) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", orderSequence, OCCURRED_AT,
                42L, OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                new BigDecimal("1.00"), null, "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent createForOrderId(String orderId) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, orderId, 1L, OCCURRED_AT, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD, new BigDecimal("1.00"),
                null, "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent createForOccurredAt(Instant occurredAt) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, occurredAt, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD, new BigDecimal("1.00"),
                null, "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent createForOrderStatus(OrderStatus orderStatus) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, OCCURRED_AT, 42L,
                orderStatus, PaymentStatus.PENDING, PaymentMethod.COD, new BigDecimal("1.00"),
                null, "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent createForPaymentStatus(PaymentStatus paymentStatus) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, OCCURRED_AT, 42L,
                OrderStatus.PENDING, paymentStatus, PaymentMethod.COD, new BigDecimal("1.00"),
                null, "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent createForPaymentMethod(PaymentMethod paymentMethod) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, OCCURRED_AT, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, paymentMethod, new BigDecimal("1.00"),
                null, "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent createForType(OrderEventType eventType) {
        return OrderEvent.create(eventType, "ORD_123", 1L, OCCURRED_AT, 42L, OrderStatus.PENDING,
                PaymentStatus.PENDING, PaymentMethod.COD, new BigDecimal("1.00"), null,
                "corr-123", "CHECKOUT_WEB");
    }

    private static OrderEvent createForCorrelationId(String correlationId) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, OCCURRED_AT, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD, new BigDecimal("1.00"),
                null, correlationId, "CHECKOUT_WEB");
    }

    private static OrderEvent createForEntryPoint(String entryPoint) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, OCCURRED_AT, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD, new BigDecimal("1.00"),
                null, "corr-123", entryPoint);
    }

    private static OrderEvent createWithReason(OrderEventType eventType, OrderEventReason reason) {
        return OrderEvent.create(eventType, "ORD_123", 1L, OCCURRED_AT, 42L, OrderStatus.PENDING,
                PaymentStatus.PENDING, PaymentMethod.COD, new BigDecimal("1.00"), reason,
                "corr-123", "CHECKOUT_WEB");
    }
}
