package com.project.dacn.dto.response.payment;

import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String orderId,
        PaymentMethod method,
        PaymentStatus status,
        BigDecimal amount,
        String transactionId,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt) {
}
