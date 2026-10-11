package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RefreshTokenCreateRequest(
        @NotBlank String userId,
        @NotBlank String tokenHash,
        @NotNull Instant expiresAt,
        String userAgent,
        String ip) {
}
