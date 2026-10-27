package com.project.dacn.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        @Schema(example = "swagger.customer@example.com")
        String email,

        @NotBlank(message = "password is required")
        @Schema(example = "Swagger123!")
        String password
) {
}
