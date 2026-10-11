package com.project.dacn.dto.request.catalog;

public record CategoryUpdateRequest(
        String name,
        String slug,
        String parentCategoryId,
        Boolean deleted) {
}
