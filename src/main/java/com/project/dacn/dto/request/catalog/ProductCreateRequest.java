package com.project.dacn.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;

public record ProductCreateRequest(
        @NotBlank String name,
        @NotBlank String description,
        String detailDescription,
        String thumbnail,
        String categoryId) {
}
