package com.project.dacn.integration.payment;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.Payment;
import com.project.dacn.repository.order.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Idempotent settlement shared by provider IPN handlers. Only a verified
 * callback should reach this service.
 */
@Service
@RequiredArgsConstructor
public class PaymentSettlementService {

    public enum Result {
        SETTLED, ALREADY_SETTLED, NOT_FOUND, AMOUNT_MISMATCH
    }

    private final PaymentRepository paymentRepository;

    @Transactional
    public Result settle(String orderId, BigDecimal amount, boolean success, String transactionId) {
        Payment payment = paymentRepository.findByOrder_OrderId(orderId).orElse(null);
        if (payment == null) {
            return Result.NOT_FOUND;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return Result.ALREADY_SETTLED;
        }
        if (success && amount != null && payment.getAmount().compareTo(amount) != 0) {
            return Result.AMOUNT_MISMATCH;
        }

        payment.setStatus(success ? PaymentStatus.CONFIRMED : PaymentStatus.FAILED);
        payment.setTransactionId(transactionId);
        if (success) {
            payment.setPaidAt(Instant.now());
        }
        paymentRepository.save(payment);

        if (success) {
            Order order = payment.getOrder();
            order.setStatus(OrderStatus.CONFIRMED);
        }
        return Result.SETTLED;
    }
}
