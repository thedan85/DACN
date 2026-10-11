package com.project.dacn.modules.catalog.dto.response;

import com.project.dacn.modules.common.enums.ProductStatus;

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
