package com.project.dacn.dto.request.cart;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

public record CartItemUpdateRequest(
        @Positive
        @Schema(example = "3")
        Integer quantity) {
}
