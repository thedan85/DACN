package com.project.dacn.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AttributeValueCreateRequest(
        @NotBlank String attributeId,
        @NotBlank String value) {
}
