package com.project.dacn.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;

public record AttributeCreateRequest(
        @NotBlank String name) {
}
