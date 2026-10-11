package com.project.dacn.modules.engagement.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReviewCreateRequest(
        @NotBlank String variantId,
        @NotBlank String customerId,
        String orderItemId,
        @NotNull @Min(1) @Max(5) Integer rating,
        @NotBlank String comment) {
}
