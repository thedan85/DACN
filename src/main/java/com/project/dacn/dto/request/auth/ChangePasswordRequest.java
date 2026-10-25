package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "current password is required")
        String currentPassword,

        @NotBlank(message = "new password is required")
        @Size(min = 8, max = 128, message = "new password must be 8-128 characters")
        String newPassword
) {
}
