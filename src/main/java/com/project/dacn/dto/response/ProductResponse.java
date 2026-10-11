package com.project.dacn.dto.response;

import com.project.dacn.common.constant.ProductStatus;

import java.time.Instant;

public record ProductResponse(
        String productId,
        String name,
        String description,
        String thumbnail,
        ProductStatus status,
        String categoryId,
        String categoryName,
        Instant createdAt,
        Instant updatedAt) {
}
