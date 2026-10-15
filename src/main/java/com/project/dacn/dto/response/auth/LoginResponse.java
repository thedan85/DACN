package com.project.dacn.dto.response.auth;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String tokenType
) {
}
