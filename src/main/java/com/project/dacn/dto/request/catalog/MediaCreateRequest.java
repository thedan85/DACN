package com.project.dacn.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;

public record MediaCreateRequest(
        @NotBlank String url,
        String productId,
        String variantId) {
}
