package com.project.dacn.dto.request.cart;

import jakarta.validation.constraints.NotBlank;

public record CartCreateRequest(
        @NotBlank String customerId) {
}
