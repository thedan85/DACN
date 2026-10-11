package com.project.dacn.modules.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record OrderItemCreateRequest(
        @NotBlank String orderId,
        @NotBlank String variantId,
        @NotNull @Positive Integer quantity,
        @NotNull @PositiveOrZero BigDecimal unitPrice) {
}
