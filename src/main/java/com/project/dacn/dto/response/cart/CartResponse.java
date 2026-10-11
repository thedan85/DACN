package com.project.dacn.dto.response.cart;

import java.time.Instant;

public record CartResponse(
        String cartId,
        String customerId,
        String customerName,
        Instant createdAt,
        Instant updatedAt) {
}
