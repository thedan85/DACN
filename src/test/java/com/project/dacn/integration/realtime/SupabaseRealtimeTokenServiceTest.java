package com.project.dacn.integration.realtime;

import com.project.dacn.common.time.DatabaseClock;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupabaseRealtimeTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:15:30.987Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static KeyPair keyPair;
    private static String privateKeyBase64;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    @Test
    void mintsEs256AuthenticatedTokenWithExactPayload() throws Exception {
        SupabaseRealtimeTokenService service = new SupabaseRealtimeTokenService(props(validUrl()), clockAt(NOW));
        List<String> productIds = List.of("product-a", "product-b");

        RealtimeToken token = service.mint(new RealtimeTokenClaims(42L, productIds, true));

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .clock(() -> Date.from(NOW))
                .build()
                .parseSignedClaims(token.accessToken());
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES256");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("test-kid");

        JsonNode payload = rawPayload(token.accessToken());
        assertThat(payload.get("role").asText()).isEqualTo("authenticated");
        assertThat(payload.get("aud").isTextual()).isTrue();
        assertThat(payload.get("aud").asText()).isEqualTo("realtime");
        assertThat(payload.get("iat").isNumber()).isTrue();
        assertThat(payload.get("exp").isNumber()).isTrue();
        assertThat(payload.get("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(payload.get("exp").asLong()).isEqualTo(payload.get("iat").asLong() + 300);
        assertThat(token.expiresAt()).isEqualTo(Instant.ofEpochSecond(payload.get("exp").asLong()));
        assertThat(payload.get("jti").asText()).isEqualTo(token.jti().toString());
        assertThat(payload.get("stock_product_ids")).hasSize(2);
        assertThat(payload.get("stock_product_ids").get(0).asText()).isEqualTo("product-a");
        assertThat(payload.get("stock_product_ids").get(1).asText()).isEqualTo("product-b");
        assertThat(payload.get("inbox_allowed").asBoolean()).isTrue();
        assertThat(payload.get("app_user_id").isTextual()).isTrue();
        assertThat(payload.get("app_user_id").asText()).isEqualTo("42");
        assertThat(payload.has("sub")).isFalse();
        assertThat(parsed.getPayload().getAudience()).containsExactly("realtime");
        assertThat(parsed.getPayload().getIssuedAt().toInstant()).isEqualTo(token.expiresAt().minusSeconds(300));
        assertThat(parsed.getPayload().getExpiration().toInstant()).isEqualTo(token.expiresAt());
        assertThat(parsed.getPayload().getId()).isEqualTo(token.jti().toString());
    }

    @Test
    void mintsGuestTokenWithoutAppUserId() throws Exception {
        SupabaseRealtimeTokenService service = new SupabaseRealtimeTokenService(props(validUrl()), clockAt(NOW));

        RealtimeToken token = service.mint(new RealtimeTokenClaims(null, List.of(), false));

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .clock(() -> Date.from(NOW))
                .build()
                .parseSignedClaims(token.accessToken());
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES256");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("test-kid");

        JsonNode payload = rawPayload(token.accessToken());
        assertThat(payload.get("role").asText()).isEqualTo("anon");
        assertThat(payload.get("aud").isTextual()).isTrue();
        assertThat(payload.get("aud").asText()).isEqualTo("realtime");
        assertThat(payload.get("iat").isNumber()).isTrue();
        assertThat(payload.get("exp").isNumber()).isTrue();
        assertThat(payload.get("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(payload.get("exp").asLong()).isEqualTo(payload.get("iat").asLong() + 300);
        assertThat(token.expiresAt()).isEqualTo(Instant.ofEpochSecond(payload.get("exp").asLong()));
        assertThat(payload.get("jti").asText()).isEqualTo(token.jti().toString());
        assertThat(payload.has("app_user_id")).isFalse();
        assertThat(payload.has("sub")).isFalse();
        assertThat(payload.get("stock_product_ids").isArray()).isTrue();
        assertThat(payload.get("stock_product_ids")).isEmpty();
        assertThat(payload.get("inbox_allowed").asBoolean()).isFalse();
    }

    @Test
    void guestCannotReceiveInboxGrantEvenWhenClaimsRequestIt() throws Exception {
        SupabaseRealtimeTokenService service = new SupabaseRealtimeTokenService(props(validUrl()), clockAt(NOW));

        RealtimeToken token = service.mint(new RealtimeTokenClaims(null, List.of("p1"), true));

        JsonNode payload = rawPayload(token.accessToken());
        assertThat(payload.get("inbox_allowed").asBoolean()).isFalse();
    }

    @Test
    void expiredTokenFailsVerification() {
        SupabaseRealtimeTokenService service = new SupabaseRealtimeTokenService(props(validUrl()), clockAt(NOW));
        RealtimeToken token = service.mint(new RealtimeTokenClaims(null, List.of("p1"), true));
        Date afterExpiry = Date.from(token.expiresAt().plusSeconds(1));

        assertThatThrownBy(() -> Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .clock(() -> afterExpiry)
                .build()
                .parseSignedClaims(token.accessToken()))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void acceptsPemArmoredKeyWithLiteralNewlines() {
        RealtimeProperties properties = baseProps();
        properties.setPrivateKey("-----BEGIN PRIVATE KEY-----\\n"
                + privateKeyBase64
                + "\\n-----END PRIVATE KEY-----");
        properties.validate();

        SupabaseRealtimeTokenService service = new SupabaseRealtimeTokenService(properties, clockAt(NOW));
        RealtimeToken token = service.mint(new RealtimeTokenClaims(null, List.of("p1"), true));

        assertThat(Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .clock(() -> Date.from(NOW))
                .build()
                .parseSignedClaims(token.accessToken())
                .getHeader()
                .getAlgorithm()).isEqualTo("ES256");
    }

    @Test
    void claimsCopyProductIdsAndKeepListNonNull() {
        List<String> mutable = new ArrayList<>(List.of("p1"));
        RealtimeTokenClaims claims = new RealtimeTokenClaims(null, mutable, false);
        mutable.add("p2");

        assertThat(claims.productIds()).containsExactly("p1");
        assertThat(new RealtimeTokenClaims(null, null, false).productIds()).isEmpty();
    }

    @Test
    void enabledConfigurationRejectsMissingOrMalformedValues() {
        assertInvalid(() -> {
            RealtimeProperties properties = baseProps();
            properties.setSupabaseUrl(" ");
            properties.validate();
        });
        assertInvalid(() -> {
            RealtimeProperties properties = baseProps();
            properties.setSupabaseUrl("not a url");
            properties.validate();
        });
        assertInvalid(() -> {
            RealtimeProperties properties = baseProps();
            properties.setSigningKeyId(" ");
            properties.validate();
        });
        assertInvalid(() -> {
            RealtimeProperties properties = baseProps();
            properties.setPrivateKey(" ");
            properties.validate();
        });
        assertInvalid(() -> {
            RealtimeProperties properties = baseProps();
            properties.setPrivateKey("!!!not-base64!!!");
            properties.validate();
        });
        assertInvalid(() -> {
            RealtimeProperties properties = baseProps();
            properties.setPrivateKey(Base64.getEncoder().encodeToString("hello".getBytes()));
            properties.validate();
        });
    }

    @Test
    void enabledConfigurationRejectsEcKeyNotUsableByEs256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        RealtimeProperties properties = baseProps();
        properties.setPrivateKey(Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded()));

        assertInvalid(properties::validate);
    }

    @Test
    void enabledConfigurationRequiresExactly300SecondTtl() {
        for (Duration ttl : List.of(Duration.ofSeconds(299), Duration.ofSeconds(301),
                Duration.ofMillis(300001), Duration.ZERO, Duration.ofSeconds(-1))) {
            RealtimeProperties properties = baseProps();
            properties.setTokenTtl(ttl);
            assertInvalid(properties::validate);
        }
        RealtimeProperties properties = baseProps();
        properties.setTokenTtl(null);
        assertInvalid(properties::validate);
    }

    @Test
    void disabledConfigurationAllowsBlankValuesAndSkipsKeyParsing() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setSupabaseUrl(" ");
        properties.setSigningKeyId("");
        properties.setPrivateKey("!!!not-base64!!!");

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getTokenTtl()).hasSeconds(300);
        assertThat(properties.getMaxStockTopics()).isEqualTo(24);
        assertThat(properties.getMaxReceiversPerTopic()).isEqualTo(20);
        assertThat(properties.getMaxRequestedProducts()).isEqualTo(24);
        assertThat(properties.getMaxBodyBytes()).isEqualTo(32768);
    }

    @Test
    void acceptsHttpsAndLoopbackHttpUrls() {
        for (String url : List.of(
                "https://project.supabase.co",
                "https://project.supabase.co:443",
                "https://project.supabase.co:1",
                "https://project.supabase.co:65535",
                "http://localhost:54321",
                "http://127.0.0.1:54321",
                "http://[::1]:54321")) {
            RealtimeProperties properties = props(url);
            assertThatCode(properties::validate).as(url).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsInsecureOrAmbiguousUrls() {
        for (String url : List.of(
                "http://project.supabase.co",
                "https://project.supabase.co:65536",
                "https://user:secret@project.supabase.co",
                "https://project.supabase.co/rest/v1",
                "https://project.supabase.co?apikey=secret",
                "https://project.supabase.co#fragment",
                "ftp://project.supabase.co",
                "project.supabase.co")) {
            RealtimeProperties properties = baseProps();
            properties.setSupabaseUrl(url);
            assertInvalid(properties::validate);
        }
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(RealtimeConfigurationException.class);
    }

    private static RealtimeProperties props(String url) {
        RealtimeProperties properties = baseProps();
        properties.setSupabaseUrl(url);
        properties.validate();
        return properties;
    }

    private static RealtimeProperties baseProps() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setEnabled(true);
        properties.setSupabaseUrl(validUrl());
        properties.setSigningKeyId("test-kid");
        properties.setPrivateKey(privateKeyBase64);
        return properties;
    }

    private static String validUrl() {
        return "https://project.supabase.co";
    }

    private static DatabaseClock clockAt(Instant instant) {
        return new DatabaseClock(null) {
            @Override
            public Instant now() {
                return instant;
            }
        };
    }

    private static JsonNode rawPayload(String token) throws Exception {
        String[] parts = token.split("\\.");
        return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }
}
