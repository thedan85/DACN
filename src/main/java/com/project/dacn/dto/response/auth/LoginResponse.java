package com.project.dacn.dto.response.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record LoginResponse(
        String accessToken,
        @JsonIgnore String refreshToken,
        long expiresIn,
        String tokenType
) {
}
