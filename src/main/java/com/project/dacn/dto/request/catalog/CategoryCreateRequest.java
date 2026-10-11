package com.project.dacn.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;

public record CategoryCreateRequest(
        @NotBlank String name,
        @NotBlank String slug,
        String parentCategoryId) {
}
