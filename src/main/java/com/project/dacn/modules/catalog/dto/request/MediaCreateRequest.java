package com.project.dacn.modules.catalog.dto.request;

import jakarta.validation.constraints.NotBlank;

public record MediaCreateRequest(
        @NotBlank String url,
        String productId,
        String variantId) {
}
