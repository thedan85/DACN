package com.project.dacn.repository.catalog;

import com.project.dacn.common.constant.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One flat row of the product detail base graph: product, category, thumbnail,
 * product attributes and variants in a single query. Attribute rows repeat per
 * variant; the service groups them by business id.
 */
public record ProductDetailBaseRow(
        String productId,
        String slug,
        String name,
        String description,
        String detailDescription,
        ProductStatus status,
        String categoryId,
        String categoryName,
        String thumbnail,
        String thumbnailMediaId,
        Instant createdAt,
        Instant updatedAt,
        String productValueId,
        String attributeId,
        String attributeName,
        String attributeValueId,
        String attributeValue,
        Instant attributeCreatedAt,
        String variantId,
        String sku,
        BigDecimal price,
        Integer stock) {
}
