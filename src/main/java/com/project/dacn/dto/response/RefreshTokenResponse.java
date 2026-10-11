package com.project.dacn.dto.response;

import java.time.Instant;

public record RefreshTokenResponse(
        String tokenId,
        String userId,
        Instant expiresAt,
        Boolean revoked,
        String userAgent,
        String ip,
        Instant createdAt) {
}
