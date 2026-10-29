package com.project.dacn.security;

import com.project.dacn.common.exception.InvalidTokenException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
public class GoogleTokenVerifier {

    private final JwtDecoder decoder;
    private final String clientId;

    public GoogleTokenVerifier(
            @Qualifier("googleIdTokenDecoder") JwtDecoder decoder,
            @Value("${app.auth.google.client-id:}") String clientId) {
        this.decoder = decoder;
        this.clientId = clientId;
    }

    public GoogleIdentity verify(String idToken) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("app.auth.google.client-id is not configured");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }

        String email = jwt.getClaimAsString("email");
        Object verified = jwt.getClaim("email_verified");
        if (email == null || email.isBlank() || !Boolean.TRUE.equals(verified)) {
            throw new InvalidTokenException();
        }
        return new GoogleIdentity(email.trim());
    }

    public record GoogleIdentity(String email) {
    }
}
