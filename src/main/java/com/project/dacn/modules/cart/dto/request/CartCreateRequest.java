package com.project.dacn.modules.cart.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CartCreateRequest(
        @NotBlank String customerId) {
}
