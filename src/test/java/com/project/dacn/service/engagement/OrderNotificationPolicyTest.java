package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.service.engagement.OrderNotificationPolicy.ChannelDecisions;
import com.project.dacn.service.engagement.OrderNotificationPolicy.CurrentOrderState;
import com.project.dacn.service.engagement.OrderNotificationPolicy.EmailEligibility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.project.dacn.common.constant.OrderStatus.CANCELLED;
import static com.project.dacn.common.constant.OrderStatus.CONFIRMED;
import static com.project.dacn.common.constant.OrderStatus.PENDING;
import static com.project.dacn.common.constant.PaymentMethod.COD;
import static com.project.dacn.common.constant.PaymentMethod.VIETQR;
import static com.project.dacn.common.constant.PaymentStatus.FAILED;
import static com.project.dacn.event.OrderEventType.ORDER_COMPLETED;
import static com.project.dacn.event.OrderEventType.ORDER_CONFIRMED;
import static com.project.dacn.event.OrderEventType.ORDER_EXPIRED;
import static com.project.dacn.event.OrderEventType.ORDER_PLACED;
import static com.project.dacn.event.OrderEventType.ORDER_REFUNDED;
import static com.project.dacn.event.OrderEventType.ORDER_REJECTED;
import static com.project.dacn.event.OrderEventType.ORDER_SHIPPED;
import static com.project.dacn.event.OrderEventType.PAYMENT_FAILED;
import static com.project.dacn.event.OrderEventType.REFUND_REQUIRED;
import static com.project.dacn.service.engagement.OrderNotificationPolicy.EmailEligibility.ELIGIBLE;
import static com.project.dacn.service.engagement.OrderNotificationPolicy.EmailEligibility.SKIPPED;
import static org.assertj.core.api.Assertions.assertThat;

class OrderNotificationPolicyTest {

    private static final Instant OCCURRED = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-03-01T00:10:00Z");

    private final OrderNotificationPolicy policy = new OrderNotificationPolicy();

    @Test
    void customerInAppForEverySupportedEvent() {
        for (OrderEventType type : OrderEventType.values()) {
            for (PaymentMethod method : methodsFor(type)) {
                assertThat(policy.channels(event(type, method)).customerInApp())
                        .as("customer in-app for %s/%s", type, method)
                        .isTrue();
            }
        }
    }

    @Test
    void companyNewOrderForEveryPaymentMethod() {
        for (PaymentMethod method : PaymentMethod.values()) {
            assertThat(policy.channels(event(ORDER_PLACED, method)).companyInApp())
                    .as("company new-order notice for %s", method)
                    .isTrue();
        }
    }

    @Test
    void companyStatusAndActionNoticesForAllNonPlacedEvents() {
        for (OrderEventType type : List.of(ORDER_CONFIRMED, ORDER_REJECTED, ORDER_EXPIRED,
                PAYMENT_FAILED, REFUND_REQUIRED, ORDER_COMPLETED, ORDER_SHIPPED, ORDER_REFUNDED)) {
            assertThat(policy.channels(event(type, VIETQR)).companyInApp())
                    .as("company in-app for %s", type)
                    .isTrue();
        }
    }

    @Test
    void noCompanyEmailForAnyEvent() {
        for (OrderEventType type : OrderEventType.values()) {
            for (PaymentMethod method : methodsFor(type)) {
                assertThat(policy.channels(event(type, method)).companyEmail())
                        .as("company email for %s/%s", type, method)
                        .isFalse();
            }
        }
    }

    @Test
    void customerEmailMatrix() {
        for (OrderEventType type : List.of(ORDER_PLACED, ORDER_CONFIRMED, ORDER_REJECTED,
                ORDER_EXPIRED, PAYMENT_FAILED, ORDER_SHIPPED, ORDER_REFUNDED)) {
            assertThat(policy.channels(event(type, VIETQR)).customerEmail())
                    .as("customer email for %s", type)
                    .isTrue();
        }
        assertThat(policy.channels(event(REFUND_REQUIRED, VIETQR)).customerEmail()).isFalse();
        assertThat(policy.channels(event(ORDER_COMPLETED, VIETQR)).customerEmail()).isFalse();
    }

    @Test
    void futureShippedAndRefundedRemainContractOnly() {
        ChannelDecisions shipped = policy.channels(event(ORDER_SHIPPED, VIETQR));
        ChannelDecisions refunded = policy.channels(event(ORDER_REFUNDED, VIETQR));
        assertThat(shipped.customerEmail()).isTrue();
        assertThat(refunded.customerEmail()).isTrue();
        assertThat(shipped.companyEmail()).isFalse();
        assertThat(refunded.companyEmail()).isFalse();
        // No producer or fake-success endpoint exists for these in v1 (verified in report).
    }

    @Test
    void eligiblePendingOnlineBeforeDeadline() {
        assertThat(policy.emailEligibility(placed(VIETQR), current(PENDING, PaymentStatus.PENDING, DEADLINE),
                true, DEADLINE.minusNanos(1))).isEqualTo(ELIGIBLE);
    }

    @Test
    void exactOnlineDeadlineAndAfterAreSkipped() {
        assertThat(policy.emailEligibility(placed(VIETQR), current(PENDING, PaymentStatus.PENDING, DEADLINE),
                true, DEADLINE)).isEqualTo(SKIPPED);
        assertThat(policy.emailEligibility(placed(VIETQR), current(PENDING, PaymentStatus.PENDING, DEADLINE),
                true, DEADLINE.plusNanos(1))).isEqualTo(SKIPPED);
    }

    @Test
    void nullOrInactiveLegacyDeadlineIsSkipped() {
        assertThat(policy.emailEligibility(placed(VIETQR), current(PENDING, PaymentStatus.PENDING, null),
                true, DEADLINE.minusNanos(1))).isEqualTo(SKIPPED);
        assertThat(policy.emailEligibility(placed(VIETQR), current(PENDING, PaymentStatus.PENDING, DEADLINE),
                false, DEADLINE.minusNanos(1))).isEqualTo(SKIPPED);
    }

    @Test
    void pendingCodIsAllowedWithoutAnyDeadlinePredicate() {
        assertThat(policy.emailEligibility(placed(COD), current(PENDING, PaymentStatus.PENDING, null),
                false, DEADLINE.plusSeconds(3600))).isEqualTo(ELIGIBLE);
        assertThat(policy.emailEligibility(placed(COD), current(PENDING, PaymentStatus.PENDING, DEADLINE),
                true, DEADLINE.plusSeconds(3600))).isEqualTo(ELIGIBLE);
    }

    @Test
    void codStillRequiresBothPendingStatuses() {
        assertThat(policy.emailEligibility(placed(COD), current(CONFIRMED, PaymentStatus.PENDING, DEADLINE),
                true, DEADLINE.minusNanos(1))).isEqualTo(SKIPPED);
    }

    @Test
    void obsoleteReceiptAfterOnlineConfirmationIsSkipped() {
        assertThat(policy.emailEligibility(placed(VIETQR), current(CONFIRMED, PaymentStatus.CONFIRMED, DEADLINE),
                true, DEADLINE.minusNanos(1))).isEqualTo(SKIPPED);
    }

    @Test
    void obsoleteReceiptAfterCodAcceptanceIsSkipped() {
        assertThat(policy.emailEligibility(placed(COD), current(CONFIRMED, PaymentStatus.PENDING, DEADLINE),
                true, DEADLINE.minusNanos(1))).isEqualTo(SKIPPED);
    }

    @Test
    void obsoleteReceiptAfterCancellationIsSkipped() {
        assertThat(policy.emailEligibility(placed(VIETQR), current(CANCELLED, FAILED, DEADLINE),
                true, DEADLINE.minusNanos(1))).isEqualTo(SKIPPED);
    }

    @Test
    void queuedConfirmationAfterCancellationIsSkipped() {
        OrderEvent confirmation = event(ORDER_CONFIRMED, VIETQR, CONFIRMED, PaymentStatus.CONFIRMED, "ORD_c");
        assertThat(policy.emailEligibility(confirmation, current(CANCELLED, PaymentStatus.CONFIRMED, null),
                true, DEADLINE)).isEqualTo(SKIPPED);
    }

    @Test
    void cancellationNoticeStillEligibleForCancelledOrder() {
        OrderEvent rejected = event(ORDER_REJECTED, VIETQR, CANCELLED,
                PaymentStatus.PENDING, "ORD_rej");
        assertThat(policy.emailEligibility(rejected, current(CANCELLED,
                PaymentStatus.PENDING, null), true, DEADLINE)).isEqualTo(ELIGIBLE);
    }

    @Test
    void channelDeniedEventIsSkippedForEmail() {
        assertThat(policy.emailEligibility(event(REFUND_REQUIRED, VIETQR, CONFIRMED,
                        PaymentStatus.REFUND_REQUIRED, "ORD_r"),
                current(CONFIRMED, PaymentStatus.REFUND_REQUIRED, null),
                true, DEADLINE)).isEqualTo(SKIPPED);
    }

    private static OrderEvent placed(PaymentMethod method) {
        return event(ORDER_PLACED, method, PENDING, PaymentStatus.PENDING, "ORD_p");
    }

    private static OrderEvent event(OrderEventType type, PaymentMethod method) {
        OrderStatus orderStatus = switch (type) {
            case ORDER_CONFIRMED -> CONFIRMED;
            case ORDER_COMPLETED -> OrderStatus.COMPLETED;
            default -> PENDING;
        };
        PaymentStatus paymentStatus = type == ORDER_CONFIRMED
                ? (method == COD ? PaymentStatus.PENDING : PaymentStatus.CONFIRMED)
                : PaymentStatus.PENDING;
        return event(type, method, orderStatus, paymentStatus, "ORD_" + type);
    }

    private static OrderEvent event(
            OrderEventType type, PaymentMethod method,
            OrderStatus orderStatus, PaymentStatus paymentStatus, String orderId) {
        OrderEventReason reason = switch (type) {
            case ORDER_REJECTED -> OrderEventReason.STAFF_REJECTED;
            case ORDER_EXPIRED -> OrderEventReason.PAYMENT_EXPIRED;
            case PAYMENT_FAILED -> OrderEventReason.PAYMENT_FAILED;
            default -> null;
        };
        return OrderEvent.create(type, orderId, 1, OCCURRED, 42L, orderStatus, paymentStatus,
                method, new BigDecimal("10.00"), reason, "t2-corr", "TEST");
    }

    private static CurrentOrderState current(
            OrderStatus orderStatus, PaymentStatus paymentStatus, Instant expiresAt) {
        return new CurrentOrderState(orderStatus, paymentStatus, expiresAt);
    }

    private static List<PaymentMethod> methodsFor(OrderEventType type) {
        return type == ORDER_PLACED ? List.of(COD, VIETQR) : List.of(VIETQR, COD);
    }
}
