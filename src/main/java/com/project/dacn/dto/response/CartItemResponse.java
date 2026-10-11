package com.project.dacn.dto.response;

import java.time.Instant;

public record CartItemResponse(
        String cartItemId,
        String cartId,
        String variantId,
        String sku,
        Integer quantity,
        Instant createdAt,
        Instant updatedAt) {
}
