package com.project.dacn.dto.request;

import jakarta.validation.constraints.NotBlank;

public record MediaCreateRequest(
        @NotBlank String url,
        String productId,
        String variantId) {
}
