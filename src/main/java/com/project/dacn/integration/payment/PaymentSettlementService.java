package com.project.dacn.integration.payment;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
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
        SETTLED, ALREADY_SETTLED, NOT_FOUND, ORDER_CANCELLED, AMOUNT_MISMATCH
    }

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public Result settle(String orderId, BigDecimal amount, boolean success, String transactionId) {
        Payment payment = paymentRepository.findByOrder_OrderId(orderId).orElse(null);
        if (payment == null) {
            return Result.NOT_FOUND;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return Result.ALREADY_SETTLED;
        }
        if (success && (amount == null || payment.getAmount().compareTo(amount) != 0)) {
            return Result.AMOUNT_MISMATCH;
        }

        if (success) {
            // Serialize with staff cancellation: a cancelled order must never be
            // confirmed by a late IPN.
            Order order = orderRepository.findWithLockByOrderId(orderId).orElse(null);
            if (order == null || order.getStatus() == OrderStatus.CANCELLED) {
                return Result.ORDER_CANCELLED;
            }
        }

        // Conditional UPDATE so duplicate/concurrent IPNs settle at most once.
        int updated = paymentRepository.settle(
                payment.getPaymentId(),
                PaymentStatus.PENDING,
                success ? PaymentStatus.CONFIRMED : PaymentStatus.FAILED,
                transactionId,
                success ? Instant.now() : null);
        if (updated == 0) {
            return Result.ALREADY_SETTLED;
        }

        if (success) {
            orderRepository.updateStatus(orderId, OrderStatus.PENDING, OrderStatus.CONFIRMED);
        }
        return Result.SETTLED;
    }
}
