package com.project.dacn.dto.request;

import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record VariantUpdateRequest(
        String sku,
        @PositiveOrZero BigDecimal price,
        @PositiveOrZero Integer stock,
        Boolean deleted) {
}
