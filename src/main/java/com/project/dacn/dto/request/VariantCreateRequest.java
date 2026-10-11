package com.project.dacn.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record VariantCreateRequest(
        @NotBlank String productId,
        @NotBlank String sku,
        @NotNull @PositiveOrZero BigDecimal price,
        @PositiveOrZero Integer stock) {
}
