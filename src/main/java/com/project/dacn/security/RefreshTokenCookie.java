package com.project.dacn.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the Set-Cookie header for the refresh token.
 *
 * <p>The refresh token never reaches JavaScript: it is HttpOnly, scoped to the
 * auth endpoints, and SameSite=Strict by default so cross-site requests cannot
 * replay it. A cross-site frontend (separate registrable domain) must set
 * SameSite=None with Secure; the origin allow-list remains the CSRF check.
 * The access token stays in the JSON response and is held in browser memory.
 */
@Component
public class RefreshTokenCookie {

    public static final String NAME = "refresh_token";
    private static final String PATH = "/api/v1/auth";

    private final JwtService jwtService;
    private final boolean secure;
    private final String sameSite;

    public RefreshTokenCookie(
            JwtService jwtService,
            @Value("${app.auth.cookie-secure:false}") boolean secure,
            @Value("${app.auth.cookie-same-site:Strict}") String sameSite) {
        if ("None".equalsIgnoreCase(sameSite) && !secure) {
            throw new IllegalStateException("SameSite=None requires app.auth.cookie-secure=true");
        }
        this.jwtService = jwtService;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public String create(String refreshToken) {
        return base(refreshToken)
                .maxAge(Duration.ofMillis(jwtService.getRefreshTokenExpirationMs()))
                .build()
                .toString();
    }

    public String clear() {
        return base("")
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH);
    }
}
