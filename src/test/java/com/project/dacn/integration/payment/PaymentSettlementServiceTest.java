package com.project.dacn.integration.payment;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.Payment;
import com.project.dacn.repository.order.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementServiceTest {

    @Mock
    PaymentRepository paymentRepository;

    @InjectMocks
    PaymentSettlementService service;

    private Payment payment(PaymentStatus status) {
        Order order = Order.builder().orderId("ORD_1").status(OrderStatus.PENDING).build();
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
        Payment payment = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        assertThat(service.settle("ORD_1", new BigDecimal("5000"), true, "TXN_1"))
                .isEqualTo(PaymentSettlementService.Result.SETTLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(payment.getTransactionId()).isEqualTo("TXN_1");
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(payment.getOrder().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void amountMismatchDoesNotSettle() {
        Payment payment = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        assertThat(service.settle("ORD_1", new BigDecimal("1"), true, "TXN_1"))
                .isEqualTo(PaymentSettlementService.Result.AMOUNT_MISMATCH);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(paymentRepository, never()).save(payment);
    }

    @Test
    void replayIsIdempotent() {
        Payment payment = payment(PaymentStatus.CONFIRMED);
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        assertThat(service.settle("ORD_1", new BigDecimal("5000"), true, "TXN_2"))
                .isEqualTo(PaymentSettlementService.Result.ALREADY_SETTLED);
    }
}
