package com.project.dacn.modules.engagement.dto.response;

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
