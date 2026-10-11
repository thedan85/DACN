package com.project.dacn.dto.request.order;

import com.project.dacn.common.constant.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record PaymentCreateRequest(
        @NotBlank String orderId,
        @NotNull PaymentMethod method,
        @NotNull @PositiveOrZero BigDecimal amount,
        String transactionId) {
}
