package com.project.dacn.integration.realtime;

import com.project.dacn.common.time.DatabaseClock;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupabaseRealtimeTokenService {

    private final RealtimeProperties properties;
    private final DatabaseClock clock;

    public RealtimeToken mint(RealtimeTokenClaims claims) {
        Objects.requireNonNull(claims, "claims");
        Instant issuedAt = Instant.ofEpochSecond(clock.now().getEpochSecond());
        Instant expiresAt = issuedAt.plus(properties.getTokenTtl());
        UUID jti = UUID.randomUUID();
        PrivateKey privateKey = RealtimeProperties.parsePrivateKey(properties.getPrivateKey());

        JwtBuilder builder = Jwts.builder()
                .header().keyId(properties.getSigningKeyId()).and()
                .id(jti.toString())
                .setAudience("realtime")
                .claim("role", claims.userId() == null ? "anon" : "authenticated")
                .claim("stock_product_ids", claims.productIds())
                .claim("inbox_allowed", claims.userId() != null && claims.inboxAllowed())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt));
        if (claims.userId() != null) {
            builder.claim("app_user_id", claims.userId().toString());
        }
        String accessToken = builder
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
        return new RealtimeToken(accessToken, expiresAt, jti);
    }
}
