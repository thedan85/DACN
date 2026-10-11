package com.project.dacn.modules.catalog.dto.request;

import jakarta.validation.constraints.NotBlank;

public record VariantAttributeValueCreateRequest(
        @NotBlank String variantId,
        @NotBlank String attributeId,
        @NotBlank String attributeValueId) {
}
