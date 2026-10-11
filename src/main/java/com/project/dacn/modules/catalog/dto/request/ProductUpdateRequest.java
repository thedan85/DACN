package com.project.dacn.modules.catalog.dto.request;

import com.project.dacn.modules.common.enums.ProductStatus;

public record ProductUpdateRequest(
        String name,
        String description,
        String thumbnail,
        String categoryId,
        ProductStatus status) {
}
