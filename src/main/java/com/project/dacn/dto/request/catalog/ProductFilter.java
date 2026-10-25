package com.project.dacn.dto.request.catalog;

import com.project.dacn.common.constant.ProductSort;
import com.project.dacn.common.constant.ProductStatus;

import java.math.BigDecimal;

/**
 * Query criteria for the public product list/search endpoints. Price criteria
 * refer to the lowest active variant price; see {@link ProductSort} for the
 * price ordering semantics.
 */
public record ProductFilter(
        String keyword,
        ProductStatus status,
        String categoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        ProductSort sort) {

    public ProductFilter(String keyword, ProductStatus status, String categoryId) {
        this(keyword, status, categoryId, null, null, null);
    }
}
