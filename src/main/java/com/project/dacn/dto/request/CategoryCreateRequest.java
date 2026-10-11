package com.project.dacn.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CategoryCreateRequest(
        @NotBlank String name,
        @NotBlank String slug,
        String parentCategoryId) {
}
