package com.project.dacn.modules.cart.dto.response;

import java.time.Instant;

public record CartResponse(
        String cartId,
        String customerId,
        String customerName,
        Instant createdAt,
        Instant updatedAt) {
}
