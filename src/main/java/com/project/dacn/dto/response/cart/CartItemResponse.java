package com.project.dacn.dto.response.cart;

import java.math.BigDecimal;
import java.time.Instant;

public record CartItemResponse(
        String cartItemId,
        String cartId,
        String variantId,
        String sku,
        Integer quantity,
        BigDecimal price,
        BigDecimal lineTotal,
        String productId,
        String productName,
        String thumbnail,
        Integer stock,
        Instant createdAt,
        Instant updatedAt) {
}
