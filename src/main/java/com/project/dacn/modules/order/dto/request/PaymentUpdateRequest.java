package com.project.dacn.modules.order.dto.request;

import com.project.dacn.modules.common.enums.PaymentStatus;

import java.time.Instant;

public record PaymentUpdateRequest(
        PaymentStatus status,
        String transactionId,
        Instant paidAt) {
}
