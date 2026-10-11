package com.project.dacn.dto.request.cart;

import jakarta.validation.constraints.Positive;

public record CartItemUpdateRequest(
        @Positive Integer quantity) {
}
