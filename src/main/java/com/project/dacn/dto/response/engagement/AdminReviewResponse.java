package com.project.dacn.dto.response.engagement;

import java.time.Instant;

public record AdminReviewResponse(
        String reviewId,
        String customerId,
        String customerName,
        Integer rating,
        String comment,
        String productId,
        String productName,
        String variantId,
        String variantSku,
        boolean verifiedPurchase,
        Instant createdAt,
        Instant updatedAt) {
}
