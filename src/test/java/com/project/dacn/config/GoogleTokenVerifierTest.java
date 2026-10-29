package com.project.dacn.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.project.dacn.common.exception.GoogleLoginNotConfiguredException;
import com.project.dacn.common.exception.InvalidTokenException;
import com.project.dacn.security.GoogleTokenVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleTokenVerifierTest {

    private static final String CLIENT_ID = "test-client.apps.googleusercontent.com";
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private GoogleTokenVerifier verifier(String clientId) {
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(GoogleAuthConfig.googleTokenValidators(clientId));
        return new GoogleTokenVerifier(decoder, clientId);
    }

    private String token(String issuer, String audience, boolean verified,
                         String email, Instant expiresAt) throws Exception {
        return token("google-subject", issuer, audience, verified, email, expiresAt);
    }

    private String token(String subject, String issuer, String audience, boolean verified,
                         String email, Instant expiresAt) throws Exception {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key")
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .claim("email", email)
                .claim("email_verified", verified)
                .expirationTime(Date.from(expiresAt))
                .issueTime(new Date())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void validToken_returnsSubAndEmail() throws Exception {
        String jwt = token("https://accounts.google.com", CLIENT_ID, true,
                "John.Doe@Example.com", Instant.now().plusSeconds(300));

        GoogleTokenVerifier.GoogleIdentity identity = verifier(CLIENT_ID).verify(jwt);
        assertThat(identity.sub()).isEqualTo("google-subject");
        assertThat(identity.email()).isEqualTo("John.Doe@Example.com");
    }

    @Test
    void blankSubject_rejected() throws Exception {
        String jwt = token("", "https://accounts.google.com", CLIENT_ID, true,
                "user@example.com", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> verifier(CLIENT_ID).verify(jwt))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void alternateIssuer_accepted() throws Exception {
        String jwt = token("accounts.google.com", CLIENT_ID, true,
                "user@example.com", Instant.now().plusSeconds(300));

        assertThat(verifier(CLIENT_ID).verify(jwt).email()).isEqualTo("user@example.com");
    }

    @Test
    void wrongAudience_rejected() throws Exception {
        String jwt = token("https://accounts.google.com", "another-client", true,
                "user@example.com", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> verifier(CLIENT_ID).verify(jwt))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void wrongIssuer_rejected() throws Exception {
        String jwt = token("https://evil.example.com", CLIENT_ID, true,
                "user@example.com", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> verifier(CLIENT_ID).verify(jwt))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void unverifiedEmail_rejected() throws Exception {
        String jwt = token("https://accounts.google.com", CLIENT_ID, false,
                "user@example.com", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> verifier(CLIENT_ID).verify(jwt))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void expiredToken_rejected() throws Exception {
        String jwt = token("https://accounts.google.com", CLIENT_ID, true,
                "user@example.com", Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> verifier(CLIENT_ID).verify(jwt))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void blankClientId_failsClosed() {
        assertThatThrownBy(() -> verifier("").verify("anything"))
                .isInstanceOf(GoogleLoginNotConfiguredException.class);
    }
}
