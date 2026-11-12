package com.project.dacn.integration.payment;

import com.project.dacn.common.constant.*;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.outbox.OrderEventOutbox;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.project.dacn.integration.payment.PaymentSettlementService.Result.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementServiceTest {
    @Mock OrderRepository orders;
    @Mock PaymentRepository payments;
    @Mock OrderItemRepository items;
    @Mock VariantRepository variants;
    @Mock OrderEventOutbox outbox;
    @Mock DatabaseClock clock;
    private PaymentSettlementService service;
    private Order order;
    private Payment payment;
    private static final Instant NOW = Instant.parse("2026-11-13T01:02:03Z");

    @BeforeEach
    void setup() {
        var lifecycle = new OrderLifecycleService(orders, payments, items, variants,
                mock(OrderResponseAssembler.class), outbox, clock, new ReservationPolicy(true, false, false));
        ReflectionTestUtils.setField(lifecycle, "transactionLockBounds", false);
        service = new PaymentSettlementService(lifecycle);
        order = Order.builder().id(1L).orderId("ORD_1").status(OrderStatus.PENDING)
                .customer(Customer.builder().user(User.builder().id(5L).build()).build())
                .total(new BigDecimal("5000")).paymentExpiresAt(NOW.plusSeconds(1)).eventSequence(1).build();
        payment = Payment.builder().id(2L).paymentId("PAY_1").order(order).method(PaymentMethod.E_WALLET)
                .status(PaymentStatus.PENDING).amount(new BigDecimal("5000")).build();
    }

    private void locked() {
        when(orders.findWithLockByOrderId("ORD_1")).thenReturn(Optional.of(order));
        when(payments.findWithLockByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));
    }
    private void reserved() {
        locked();
        when(items.findStockReleaseTargetsByOrderId("ORD_1")).thenReturn(List.of(new OrderItemRepository.StockReleaseTarget() {
            public Long getVariantId() { return 3L; }
            public Long getQuantity() { return 2L; }
        }));
        when(clock.now()).thenReturn(NOW);
    }
    private PaymentSettlementService.Result settle(boolean success, String reference) {
        return service.settle("ORD_1", new BigDecimal("5000"), success, reference);
    }

    @Test void confirmedCallbackSettlesPaymentAndOrder() {
        reserved();
        assertThat(settle(true, "TX1")).isEqualTo(SETTLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        assertThat(payment.getTransactionId()).isEqualTo("TX1");
        assertThat(order.getEventSequence()).isEqualTo(2);
        verifyNoInteractions(variants);
        var locks = inOrder(orders, payments, clock);
        locks.verify(orders).findWithLockByOrderId("ORD_1");
        locks.verify(payments).findWithLockByOrder_OrderId("ORD_1");
        locks.verify(clock).now();
    }
    @Test void amountMismatchDoesNotSettleEvenForFailure() {
        locked();
        assertThat(service.settle("ORD_1", BigDecimal.ONE, false, null)).isEqualTo(AMOUNT_MISMATCH);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(items, variants, clock, outbox);
    }
    @Test void invalidReceivedReferenceDoesNotMutate() {
        locked();
        assertThat(settle(true, " ")).isEqualTo(NOT_FOUND);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(items, variants, clock, outbox);
    }
    @Test void lateSuccessOnProvenReleasedOrderRecordsRefund() {
        reserved();
        order.setStatus(OrderStatus.CANCELLED);
        order.setStockReleasedAt(NOW.minusSeconds(5));
        order.setCancellationReason(com.project.dacn.event.OrderEventReason.STAFF_REJECTED);
        payment.setStatus(PaymentStatus.FAILED);
        assertThat(settle(true, "TX1")).isEqualTo(REFUND_REQUIRED);
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verifyNoInteractions(variants);
    }
    @Test void equalityExpiresThenRecordsMoneyWithDistinctImmutableFacts() {
        reserved();
        order.setPaymentExpiresAt(NOW);
        when(variants.incrementStockNative(3L, 2)).thenReturn(1);
        assertThat(settle(true, "TX1")).isEqualTo(REFUND_REQUIRED);
        var facts = org.mockito.ArgumentCaptor.forClass(OrderEvent.class);
        verify(outbox, times(2)).append(eq(1L), facts.capture());
        assertThat(facts.getAllValues()).extracting(OrderEvent::orderSequence).containsExactly(2L, 3L);
        assertThat(facts.getAllValues()).extracting(OrderEvent::paymentStatus).containsExactly(PaymentStatus.FAILED, PaymentStatus.REFUND_REQUIRED);
        assertThat(order.getStockReleasedAt()).isEqualTo(NOW);
        verify(clock).now();
    }
    @Test void receivedMoneyReplaysNeverRegressOrOverwriteEvidence() {
        locked();
        payment.setStatus(PaymentStatus.CONFIRMED);
        payment.setPaidAt(NOW);
        payment.setTransactionId("TX1");
        assertThat(settle(true, "TX2")).isEqualTo(ALREADY_SETTLED);
        assertThat(settle(false, null)).isEqualTo(ALREADY_SETTLED);
        assertThat(payment.getTransactionId()).isEqualTo("TX1");
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        verifyNoInteractions(items, variants, outbox, clock);
    }
    @Test void failedCallbackReleasesWithoutReceivedMoney() {
        reserved();
        when(variants.incrementStockNative(3L, 2)).thenReturn(1);
        assertThat(settle(false, null)).isEqualTo(SETTLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getPaidAt()).isNull();
        assertThat(payment.getTransactionId()).isNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }
    @Test void unknownOrderIsNotFoundBeforeReadingPayment() {
        when(orders.findWithLockByOrderId("ORD_1")).thenReturn(Optional.empty());
        assertThat(settle(true, "TX1")).isEqualTo(NOT_FOUND);
        verifyNoInteractions(payments, items, variants, outbox, clock);
    }
    @Test void codCallbackIsNotFound() {
        locked();
        payment.setMethod(PaymentMethod.COD);
        assertThat(settle(true, "TX1")).isEqualTo(NOT_FOUND);
        verifyNoInteractions(items, variants, outbox, clock);
    }
    @Test void activatedNullDeadlineFailsClosed() {
        locked();
        order.setPaymentExpiresAt(null);
        assertThatThrownBy(() -> settle(true, "TX1")).isExactlyInstanceOf(ReservationReviewRequiredException.class);
        verifyNoInteractions(items, variants, outbox, clock);
    }
    @Test void conflictingStoredOrderAndPaymentTotalsRequireReview() {
        locked();
        lenient().when(items.findStockReleaseTargetsByOrderId("ORD_1"))
                .thenReturn(List.of(mock(OrderItemRepository.StockReleaseTarget.class)));
        lenient().when(clock.now()).thenReturn(NOW);
        payment.setAmount(new BigDecimal("4999"));
        assertThatThrownBy(() -> service.settle("ORD_1", new BigDecimal("4999"), true, "TX1"))
                .isExactlyInstanceOf(ReservationReviewRequiredException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(items, variants, outbox, clock);
    }
}
