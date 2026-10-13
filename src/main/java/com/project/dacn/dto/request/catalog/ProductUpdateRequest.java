package com.project.dacn.dto.request.catalog;

import com.project.dacn.common.constant.ProductStatus;

public record ProductUpdateRequest(
        String name,
        String description,
        String detailDescription,
        String thumbnail,
        String categoryId,
        ProductStatus status) {
}
