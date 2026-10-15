package com.project.dacn.dto.response.auth;

public record RefreshResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String tokenType
) {
}
