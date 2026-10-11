package com.project.dacn.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AttributeCreateRequest(
        @NotBlank String name) {
}
