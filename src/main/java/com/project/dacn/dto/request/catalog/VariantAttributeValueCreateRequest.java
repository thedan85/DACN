package com.project.dacn.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;

public record VariantAttributeValueCreateRequest(
        @NotBlank String variantId,
        @NotBlank String attributeId,
        @NotBlank String attributeValueId) {
}
