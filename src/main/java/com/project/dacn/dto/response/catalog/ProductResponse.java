package com.project.dacn.dto.response.catalog;

import com.project.dacn.common.constant.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        String productId,
        String slug,
        String name,
        String description,
        String detailDescription,
        String thumbnail,
        ProductStatus status,
        String categoryId,
        String categoryName,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Instant createdAt,
        Instant updatedAt) {
}
