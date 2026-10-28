package com.project.dacn.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    private static final String CLAIM_TYPE = "type";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiration-ms}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpiration;

    public String generateAccessToken(String userId, String email) {
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(String userId, String tokenId) {
        return Jwts.builder()
                .subject(userId)
                .id(tokenId)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiration))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Only access tokens may authenticate API calls. Refresh tokens are valid
     * JWTs too, so the type claim must be checked explicitly.
     */
    public boolean validateAccessToken(String token) {
        return validateToken(token) && TYPE_ACCESS.equals(extractType(token));
    }

    public boolean isRefreshToken(String token) {
        return validateToken(token) && TYPE_REFRESH.equals(extractType(token));
    }

    public String extractUserId(String token) {
        return parse(token).getSubject();
    }

    public String extractEmail(String token) {
        return parse(token).get("email", String.class);
    }

    public String extractJti(String token) {
        return parse(token).get("jti", String.class);
    }

    public String extractType(String token) {
        return parse(token).get(CLAIM_TYPE, String.class);
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpiration / 1000;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpiration;
    }

    private io.jsonwebtoken.Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
