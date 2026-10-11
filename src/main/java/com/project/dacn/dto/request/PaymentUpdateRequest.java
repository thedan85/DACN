package com.project.dacn.dto.request;

import com.project.dacn.common.constant.PaymentStatus;

import java.time.Instant;

public record PaymentUpdateRequest(
        PaymentStatus status,
        String transactionId,
        Instant paidAt) {
}
