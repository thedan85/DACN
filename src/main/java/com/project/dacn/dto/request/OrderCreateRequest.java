package com.project.dacn.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record OrderCreateRequest(
        @NotBlank String customerId,
        @NotNull @PositiveOrZero BigDecimal total,
        @NotBlank String address,
        @NotBlank String recipientName,
        @NotBlank String phone) {
}
