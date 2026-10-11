package com.project.dacn.modules.order.dto.response;

import com.project.dacn.modules.common.enums.PaymentMethod;
import com.project.dacn.modules.common.enums.PaymentStatus;

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
