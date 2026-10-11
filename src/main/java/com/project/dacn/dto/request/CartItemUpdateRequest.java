package com.project.dacn.dto.request;

import jakarta.validation.constraints.Positive;

public record CartItemUpdateRequest(
        @Positive Integer quantity) {
}
