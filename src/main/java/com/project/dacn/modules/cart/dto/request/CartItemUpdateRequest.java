package com.project.dacn.modules.cart.dto.request;

import jakarta.validation.constraints.Positive;

public record CartItemUpdateRequest(
        @Positive Integer quantity) {
}
