package com.project.dacn.dto.response.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record RefreshResponse(
        String accessToken,
        @JsonIgnore String refreshToken,
        long expiresIn,
        String tokenType
) {
}
