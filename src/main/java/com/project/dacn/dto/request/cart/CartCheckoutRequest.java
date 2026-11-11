package com.project.dacn.dto.request.cart;

import com.project.dacn.common.constant.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CartCheckoutRequest(
        @NotBlank
        @Schema(example = "123 Demo St, HCMC")
        String address,

        @NotBlank
        @Schema(example = "Swagger Customer")
        String recipientName,

        @NotBlank
        @Schema(example = "0901234567")
        String phone,

        @NotNull
        @Schema(example = "COD")
        PaymentMethod method) {
}
