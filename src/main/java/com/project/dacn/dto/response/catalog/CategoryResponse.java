package com.project.dacn.dto.response.catalog;

import java.time.Instant;

public record CategoryResponse(
        String categoryId,
        String name,
        String slug,
        String parentCategoryId,
        Boolean deleted,
        Instant createdAt,
        Instant updatedAt) {
}
