package com.project.dacn.modules.catalog.dto.request;

public record CategoryUpdateRequest(
        String name,
        String slug,
        String parentCategoryId,
        Boolean deleted) {
}
