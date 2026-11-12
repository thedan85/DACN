package com.project.dacn.integration.payment;

import com.project.dacn.service.order.OrderLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Idempotent settlement shared by provider IPN handlers. Only a verified
 * callback should reach this service.
 */
@Service
@RequiredArgsConstructor
public class PaymentSettlementService {

    public enum Result {
        SETTLED, ALREADY_SETTLED, NOT_FOUND, AMOUNT_MISMATCH, REFUND_REQUIRED
    }

    private final OrderLifecycleService lifecycle;

    public Result settle(String orderId, BigDecimal amount, boolean success, String transactionId) {
        return lifecycle.settleOnline(orderId, amount, success, transactionId);
    }
}
