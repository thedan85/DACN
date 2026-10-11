package com.project.dacn.modules.catalog.dto.response;

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
