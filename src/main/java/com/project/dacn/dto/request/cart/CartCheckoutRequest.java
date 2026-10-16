package com.project.dacn.dto.request.cart;

import com.project.dacn.common.constant.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CartCheckoutRequest(
        @NotBlank String address,
        @NotBlank String recipientName,
        @NotBlank String phone,
        @NotNull PaymentMethod method) {
}
