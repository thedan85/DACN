package com.project.dacn.dto.response;

import java.time.Instant;

public record CartResponse(
        String cartId,
        String customerId,
        String customerName,
        Instant createdAt,
        Instant updatedAt) {
}
