package com.project.dacn.modules.order.dto.request;

import com.project.dacn.modules.common.enums.PaymentMethod;
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
