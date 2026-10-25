package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must not exceed 255 characters")
        String name,

        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "phone must be 10-15 digits")
        String phone,

        @Size(max = 500, message = "address must not exceed 500 characters")
        String address
) {
}
