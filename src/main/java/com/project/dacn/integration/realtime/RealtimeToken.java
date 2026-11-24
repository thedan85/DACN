package com.project.dacn.integration.realtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RealtimeToken(String accessToken, Instant expiresAt, UUID jti) {

    public RealtimeToken {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(jti, "jti");
    }
}
