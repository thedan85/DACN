package com.project.dacn.dto.request.cart;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CartItemCreateRequest(
        @NotBlank String cartId,
        @NotBlank String variantId,
        @NotNull @Positive Integer quantity) {
}
