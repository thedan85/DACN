package com.project.dacn.dto.request.cart;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CartItemAddRequest(
        @NotNull
        @Schema(example = "ember-tetra-default")
        String variantId,

        @NotNull @Positive
        @Schema(example = "1")
        Integer quantity) {
}
