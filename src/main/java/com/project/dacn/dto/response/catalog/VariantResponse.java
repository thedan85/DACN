package com.project.dacn.dto.response.catalog;

import java.math.BigDecimal;
import java.time.Instant;

public record VariantResponse(
        String variantId,
        String sku,
        BigDecimal price,
        Integer stock,
        String productId,
        String productName,
        Boolean deleted,
        Instant createdAt,
        Instant updatedAt) {
}
