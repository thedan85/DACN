package com.project.dacn.dto.request.cart;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CartItemAddRequest(
        @NotBlank String variantId,
        @NotNull @Positive Integer quantity) {
}
