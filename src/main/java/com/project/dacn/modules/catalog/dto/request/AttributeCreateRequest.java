package com.project.dacn.modules.catalog.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AttributeCreateRequest(
        @NotBlank String name) {
}
