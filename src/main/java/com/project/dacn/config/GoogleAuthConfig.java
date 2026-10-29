package com.project.dacn.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@Configuration
public class GoogleAuthConfig {

    static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS =
            Set.of("https://accounts.google.com", "accounts.google.com");

    @Bean
    JwtDecoder googleIdTokenDecoder(@Value("${app.auth.google.client-id:}") String clientId) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(JWKS_URI)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        decoder.setJwtValidator(googleTokenValidators(clientId));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> googleTokenValidators(String clientId) {
        OAuth2TokenValidator<Jwt> issuer = jwt -> ISSUERS.contains(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.success()
                : failure("invalid issuer");
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> audienceClaim = jwt.getAudience();
            return audienceClaim != null && audienceClaim.contains(clientId)
                    ? OAuth2TokenValidatorResult.success()
                    : failure("invalid audience");
        };
        return JwtValidators.createDefaultWithValidators(issuer, audience);
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }
}
