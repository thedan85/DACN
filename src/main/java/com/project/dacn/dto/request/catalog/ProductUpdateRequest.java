package com.project.dacn.dto.request.catalog;

import com.project.dacn.common.constant.ProductStatus;

public record ProductUpdateRequest(
        String name,
        String description,
        String thumbnail,
        String categoryId,
        ProductStatus status) {
}
