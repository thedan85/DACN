package com.project.dacn.dto.response.engagement;

import java.time.Instant;

public record ReviewResponse(
        String reviewId,
        String variantId,
        String customerId,
        String orderItemId,
        Integer rating,
        String comment,
        Instant createdAt,
        Instant updatedAt) {
}
