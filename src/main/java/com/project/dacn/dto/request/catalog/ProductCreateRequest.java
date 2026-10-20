package com.project.dacn.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductCreateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank String description,
        String detailDescription,
        String thumbnail,
        String categoryId,
        @Size(max = 255) String slug) {
}
