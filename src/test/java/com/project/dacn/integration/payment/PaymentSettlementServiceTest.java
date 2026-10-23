package com.project.dacn.integration.payment;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementServiceTest {

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    OrderRepository orderRepository;

    @InjectMocks
    PaymentSettlementService service;

    private Payment payment(PaymentStatus status, OrderStatus orderStatus) {
        Order order = Order.builder().orderId("ORD_1").status(orderStatus).build();
        return Payment.builder()
                .paymentId("PAY_1")
                .order(order)
                .method(PaymentMethod.COD)
                .status(status)
                .amount(new BigDecimal("5000"))
                .build();
    }

    @Test
    void confirmedCallbackSettlesPaymentAndOrder() {
        Payment payment = payment(PaymentStatus.PENDING, OrderStatus.PENDING);
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));
        when(orderRepository.findWithLockByOrderId("ORD_1")).thenReturn(Optional.of(payment.getOrder()));
        when(paymentRepository.settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.CONFIRMED), eq("TXN_1"), any(Instant.class))).thenReturn(1);
        when(orderRepository.updateStatus("ORD_1", OrderStatus.PENDING, OrderStatus.CONFIRMED))
                .thenReturn(1);

        assertThat(service.settle("ORD_1", new BigDecimal("5000"), true, "TXN_1"))
                .isEqualTo(PaymentSettlementService.Result.SETTLED);
        verify(paymentRepository).settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.CONFIRMED), eq("TXN_1"), any(Instant.class));
        verify(orderRepository).updateStatus("ORD_1", OrderStatus.PENDING, OrderStatus.CONFIRMED);
    }

    @Test
    void amountMismatchDoesNotSettle() {
        Payment payment = payment(PaymentStatus.PENDING, OrderStatus.PENDING);
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        assertThat(service.settle("ORD_1", new BigDecimal("1"), true, "TXN_1"))
                .isEqualTo(PaymentSettlementService.Result.AMOUNT_MISMATCH);
        verify(paymentRepository, never()).settle(any(), any(), any(), any(), any());
    }

    @Test
    void cancelledOrderIsRefused() {
        Payment payment = payment(PaymentStatus.PENDING, OrderStatus.CANCELLED);
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));
        when(orderRepository.findWithLockByOrderId("ORD_1")).thenReturn(Optional.of(payment.getOrder()));

        assertThat(service.settle("ORD_1", new BigDecimal("5000"), true, "TXN_1"))
                .isEqualTo(PaymentSettlementService.Result.ORDER_CANCELLED);
        verify(paymentRepository, never()).settle(any(), any(), any(), any(), any());
        verify(orderRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void replayIsIdempotent() {
        Payment payment = payment(PaymentStatus.CONFIRMED, OrderStatus.CONFIRMED);
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        assertThat(service.settle("ORD_1", new BigDecimal("5000"), true, "TXN_2"))
                .isEqualTo(PaymentSettlementService.Result.ALREADY_SETTLED);
        verify(paymentRepository, never()).settle(any(), any(), any(), any(), any());
    }

    @Test
    void losingConcurrentIpnDoesNotConfirmOrderAgain() {
        Payment payment = payment(PaymentStatus.PENDING, OrderStatus.PENDING);
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));
        when(orderRepository.findWithLockByOrderId("ORD_1")).thenReturn(Optional.of(payment.getOrder()));
        when(paymentRepository.settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.CONFIRMED), eq("TXN_1"), any(Instant.class))).thenReturn(0);

        assertThat(service.settle("ORD_1", new BigDecimal("5000"), true, "TXN_1"))
                .isEqualTo(PaymentSettlementService.Result.ALREADY_SETTLED);
        verify(orderRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void failedCallbackMarksPaymentFailed() {
        Payment payment = payment(PaymentStatus.PENDING, OrderStatus.PENDING);
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));
        when(paymentRepository.settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.FAILED), eq((String) null), any())).thenReturn(1);

        assertThat(service.settle("ORD_1", new BigDecimal("5000"), false, null))
                .isEqualTo(PaymentSettlementService.Result.SETTLED);
        verify(orderRepository, never()).findWithLockByOrderId(any());
        verify(orderRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void unknownOrderIsNotFound() {
        when(paymentRepository.findByOrder_OrderId("ORD_X")).thenReturn(Optional.empty());

        assertThat(service.settle("ORD_X", new BigDecimal("5000"), true, "TXN_1"))
                .isEqualTo(PaymentSettlementService.Result.NOT_FOUND);
    }
}
