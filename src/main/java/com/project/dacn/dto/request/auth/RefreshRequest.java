package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {
}
