package com.project.dacn.service.catalog;

import com.project.dacn.common.constant.ProductStatus;

public record ProductFilter(
        String name,
        ProductStatus status,
        String categoryId) {
}
