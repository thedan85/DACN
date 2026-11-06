package com.project.dacn.integration.realtime;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.common.exception.InvalidTokenException;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RealtimeCredentialAuthentication {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    /**
     * The JWT filter deliberately ignores invalid tokens, so this route resolves the
     * supplied bearer explicitly: a bad or inactive token must never downgrade to guest.
     */
    public RealtimePrincipal resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return RealtimePrincipal.guest();
        }
        if (!header.startsWith(BEARER_PREFIX)) {
            throw new InvalidTokenException();
        }
        String token = header.substring(BEARER_PREFIX.length());
        String userId;
        try {
            if (!jwtService.validateAccessToken(token)) {
                throw new InvalidTokenException();
            }
            userId = jwtService.extractUserId(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException();
        }
        User user = userRepository.findByUserId(userId)
                .orElseThrow(InvalidTokenException::new);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidTokenException();
        }
        return RealtimePrincipal.authenticated(user.getId());
    }
}
