package com.project.dacn.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CSRF defence for the endpoints that authenticate with the refresh cookie.
 *
 * <p>SameSite=Strict already blocks cross-site cookie sends. This adds an
 * Origin allow-list check as defence in depth, since these endpoints change
 * server state (token rotation / revocation).
 */
@Component
public class TrustedOriginValidator {

    private final Set<String> allowedOrigins;

    public TrustedOriginValidator(
            @Value("${app.cors.allowed-origins:http://localhost:3000}") String origins) {
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(HttpServletRequest request) {
        String origin = request.getHeader("Origin");

        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new AccessDeniedException("request origin is not allowed");
        }
    }
}
