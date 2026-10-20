package com.project.dacn.dto.request.cart;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CartItemAddRequest(
        @NotNull Long variantId,
        @NotNull @Positive Integer quantity) {
}
