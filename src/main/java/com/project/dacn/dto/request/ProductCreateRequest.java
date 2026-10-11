package com.project.dacn.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ProductCreateRequest(
        @NotBlank String name,
        @NotBlank String description,
        String thumbnail,
        String categoryId) {
}
