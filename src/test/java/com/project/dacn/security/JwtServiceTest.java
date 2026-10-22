package com.project.dacn.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET =
            "unit-test-secret-key-min-256-bits-hs256-algorithm-requires-this-length";

    private JwtService service() {
        JwtService jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secret", SECRET);
        ReflectionTestUtils.setField(jwt, "accessTokenExpiration", 900_000L);
        ReflectionTestUtils.setField(jwt, "refreshTokenExpiration", 604_800_000L);
        return jwt;
    }

    @Test
    void accessTokenIsAcceptedOnlyAsAccessToken() {
        JwtService jwt = service();
        String access = jwt.generateAccessToken("USR_1", "a@example.com");

        assertThat(jwt.validateAccessToken(access)).isTrue();
        assertThat(jwt.isRefreshToken(access)).isFalse();
    }

    @Test
    void refreshTokenCannotAuthenticateApiCalls() {
        JwtService jwt = service();
        String refresh = jwt.generateRefreshToken("USR_1", "jti-1");

        assertThat(jwt.validateAccessToken(refresh)).isFalse();
        assertThat(jwt.isRefreshToken(refresh)).isTrue();
    }
}
