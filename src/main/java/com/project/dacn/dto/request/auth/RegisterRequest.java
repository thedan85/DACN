package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 128, message = "password must be 8-128 characters")
        String password,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must not exceed 255 characters")
        String name,

        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "phone must be 10-15 digits")
        String phone
) {
}
