package com.project.dacn.dto.request;

public record CategoryUpdateRequest(
        String name,
        String slug,
        String parentCategoryId,
        Boolean deleted) {
}
