package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.service.engagement.NotificationMessageRenderer.MessageFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static com.project.dacn.common.constant.OrderStatus.CANCELLED;
import static com.project.dacn.common.constant.OrderStatus.CONFIRMED;
import static com.project.dacn.common.constant.OrderStatus.PENDING;
import static com.project.dacn.common.constant.PaymentMethod.COD;
import static com.project.dacn.common.constant.PaymentMethod.VIETQR;
import static com.project.dacn.event.OrderEventType.ORDER_COMPLETED;
import static com.project.dacn.event.OrderEventType.ORDER_CONFIRMED;
import static com.project.dacn.event.OrderEventType.ORDER_EXPIRED;
import static com.project.dacn.event.OrderEventType.ORDER_PLACED;
import static com.project.dacn.event.OrderEventType.PAYMENT_FAILED;
import static com.project.dacn.event.OrderEventType.REFUND_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationMessageRendererTest {

    private static final Instant OCCURRED = Instant.parse("2026-03-01T00:00:00Z");

    private final NotificationMessageRenderer renderer = new NotificationMessageRenderer();

    @Test
    void factsAreFrozenFromEventNotALaterSnapshot() {
        OrderEvent event = OrderEvent.create(ORDER_PLACED, "ORD_facts", 7, OCCURRED, 42L,
                PENDING, PaymentStatus.PENDING, COD, new BigDecimal("12.34"), null, "t2-corr", "TEST");
        MessageFacts facts = renderer.factsOf(event);

        assertThat(facts.eventType()).isEqualTo(ORDER_PLACED);
        assertThat(facts.orderStatus()).isEqualTo(PENDING);
        assertThat(facts.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(facts.paymentMethod()).isEqualTo(COD);
        assertThat(facts.total()).isEqualTo("12.34");
        assertThat(facts.orderId()).isEqualTo("ORD_facts");
        assertThat(facts.occurredAt()).isEqualTo(OCCURRED);
    }

    @Test
    void pendingCodNeverSaysPaid() {
        MessageFacts facts = factsOf(ORDER_PLACED, COD, PENDING,
                PaymentStatus.PENDING, "ORD_cod", null);
        assertThat(renderer.customerMessage(facts))
                .contains("ORD_cod")
                .containsIgnoringCase("pending")
                .doesNotContainIgnoringCase("paid")
                .contains("10.00");
    }

    @Test
    void companyPlacedCopyNamesPaymentRoute() {
        MessageFacts cod = factsOf(ORDER_PLACED, COD, PENDING,
                PaymentStatus.PENDING, "ORD_cod", null);
        assertThat(renderer.companyMessage(cod))
                .contains("COD").containsIgnoringCase("approval")
                .doesNotContainIgnoringCase("online");

        MessageFacts online = factsOf(ORDER_PLACED, VIETQR, PENDING,
                PaymentStatus.PENDING, "ORD_online", null);
        assertThat(renderer.companyMessage(online))
                .contains("online").containsIgnoringCase("payment")
                .doesNotContain("COD");
    }

    @Test
    void onlinePaidConfirmationIsTruthful() {
        MessageFacts facts = factsOf(ORDER_CONFIRMED, VIETQR, CONFIRMED,
                PaymentStatus.CONFIRMED, "ORD_online", null);
        assertThat(renderer.customerMessage(facts))
                .containsIgnoringCase("payment received");
    }

    @Test
    void codConfirmationSaysDueNotPaid() {
        MessageFacts facts = factsOf(ORDER_CONFIRMED, COD, CONFIRMED,
                PaymentStatus.PENDING, "ORD_due", null);
        assertThat(renderer.customerMessage(facts))
                .containsIgnoringCase("due")
                .doesNotContainIgnoringCase("paid");
    }

    @Test
    void refundRequiredNeverSaysRefunded() {
        MessageFacts facts = factsOf(REFUND_REQUIRED, VIETQR, CONFIRMED,
                PaymentStatus.REFUND_REQUIRED, "ORD_refundreq", null);
        assertThat(renderer.customerMessage(facts))
                .containsIgnoringCase("review")
                .doesNotContainIgnoringCase("refunded");
        assertThat(renderer.companyMessage(facts))
                .containsIgnoringCase("action")
                .doesNotContainIgnoringCase("refunded");
    }

    @Test
    void adminCompletedNeverSaysShippedOrDelivered() {
        MessageFacts facts = factsOf(ORDER_COMPLETED, VIETQR, OrderStatus.COMPLETED,
                PaymentStatus.CONFIRMED, "ORD_done", null);
        assertThat(renderer.customerMessage(facts))
                .doesNotContainIgnoringCase("shipped")
                .doesNotContainIgnoringCase("delivered");
        assertThat(renderer.companyMessage(facts))
                .doesNotContainIgnoringCase("shipped")
                .doesNotContainIgnoringCase("delivered");
    }

    @Test
    void expiredAndFailedCopyIsHonest() {
        MessageFacts expired = factsOf(ORDER_EXPIRED, VIETQR, CANCELLED,
                PaymentStatus.PENDING, "ORD_exp", OrderEventReason.PAYMENT_EXPIRED);
        assertThat(renderer.customerMessage(expired))
                .containsIgnoringCase("cancelled")
                .containsIgnoringCase("expired");

        MessageFacts failed = factsOf(PAYMENT_FAILED, VIETQR, CANCELLED, PaymentStatus.FAILED,
                "ORD_fail", OrderEventReason.PAYMENT_FAILED);
        assertThat(renderer.customerMessage(failed))
                .containsIgnoringCase("cancelled")
                .containsIgnoringCase("failed");
    }

    @Test
    void arbitraryEventInputIsHtmlEscaped() {
        String hostile = "ORD_1<img src=x onerror=alert(1)>";
        MessageFacts facts = factsOf(ORDER_PLACED, COD, PENDING,
                PaymentStatus.PENDING, hostile, null);

        String customer = renderer.customerMessage(facts);
        String company = renderer.companyMessage(facts);
        assertThat(customer).doesNotContain("<img").contains("&lt;img");
        assertThat(company).doesNotContain("<img").contains("&lt;img");
        assertThat(customer).doesNotContain("<").doesNotContain(">");
    }

    private static MessageFacts factsOf(
            OrderEventType type, PaymentMethod method, OrderStatus orderStatus,
            PaymentStatus paymentStatus, String orderId, OrderEventReason reason) {
        OrderEvent event = OrderEvent.create(type, orderId, 1, OCCURRED, 42L, orderStatus,
                paymentStatus, method, new BigDecimal("10.00"), reason, "t2-corr", "TEST");
        return new NotificationMessageRenderer().factsOf(event);
    }
}
