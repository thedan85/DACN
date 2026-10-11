package com.project.dacn.modules.catalog.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ProductAttributeValueCreateRequest(
        @NotBlank String productId,
        @NotBlank String attributeId,
        @NotBlank String attributeValueId) {
}
