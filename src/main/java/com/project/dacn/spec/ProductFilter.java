package com.project.dacn.spec;

import com.project.dacn.common.constant.ProductStatus;

public record ProductFilter(
        String name,
        ProductStatus status,
        String categoryId) {
}
