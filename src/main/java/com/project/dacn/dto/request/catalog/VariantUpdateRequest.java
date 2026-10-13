package com.project.dacn.dto.request.catalog;

import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record VariantUpdateRequest(
        String sku,
        @PositiveOrZero BigDecimal price,
        @PositiveOrZero Integer stock) {
}
