package com.project.dacn.integration.realtime;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.realtime")
public class RealtimeProperties {

    private static final String PRIVATE_KEY_ARMOR_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_KEY_ARMOR_END = "-----END PRIVATE KEY-----";

    private boolean enabled;
    private String supabaseUrl;
    private String signingKeyId;
    private String privateKey;
    private Duration tokenTtl = Duration.ofSeconds(300);
    private int maxStockTopics = 24;
    private int maxReceiversPerTopic = 20;
    private int maxRequestedProducts = 24;
    private int maxBodyBytes = 32 * 1024;
    private String trustedClientIpHeader;

    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        validateSupabaseUrl(supabaseUrl);
        if (signingKeyId == null || signingKeyId.isBlank()) {
            throw new RealtimeConfigurationException(
                    "REALTIME_KEY_ID_INVALID",
                    "app.realtime.signing-key-id must not be blank");
        }
        if (!Duration.ofSeconds(300).equals(tokenTtl)) {
            throw new RealtimeConfigurationException(
                    "REALTIME_TOKEN_TTL_INVALID",
                    "app.realtime.token-ttl must be exactly 300 seconds");
        }
        parsePrivateKey(privateKey);
    }

    static PrivateKey parsePrivateKey(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw invalidPrivateKey(null);
        }
        try {
            String normalized = configuredKey.replace("\\n", "\n")
                    .replace(PRIVATE_KEY_ARMOR_BEGIN, "")
                    .replace(PRIVATE_KEY_ARMOR_END, "")
                    .replaceAll("\\s+", "");
            byte[] encoded = Base64.getDecoder().decode(normalized);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PrivateKey parsedKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec p256 = parameters.getParameterSpec(ECParameterSpec.class);
            if (!(parsedKey instanceof ECPrivateKey ecKey)
                    || !ecKey.getParams().getCurve().equals(p256.getCurve())
                    || !ecKey.getParams().getGenerator().equals(p256.getGenerator())
                    || !ecKey.getParams().getOrder().equals(p256.getOrder())
                    || ecKey.getParams().getCofactor() != p256.getCofactor()) {
                throw invalidPrivateKey(null);
            }
            return parsedKey;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw invalidPrivateKey(e);
        }
    }

    private static void validateSupabaseUrl(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw invalidUrl();
        }
        URI uri;
        try {
            uri = new URI(configuredUrl.trim());
        } catch (URISyntaxException e) {
            throw invalidUrl();
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank() || uri.getPort() > 65535) {
            throw invalidUrl();
        }
        String path = uri.getRawPath();
        if (uri.getRawUserInfo() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new RealtimeConfigurationException(
                    "REALTIME_URL_FORBIDDEN_PARTS",
                    "app.realtime.supabase-url must not contain credentials, a path, a query, or a fragment");
        }

        boolean https = "https".equalsIgnoreCase(scheme);
        boolean plainHttpOnLoopback = "http".equalsIgnoreCase(scheme) && isLoopback(host);
        if (!https && !plainHttpOnLoopback) {
            throw new RealtimeConfigurationException(
                    "REALTIME_URL_INSECURE",
                    "app.realtime.supabase-url must use https outside local development");
        }
    }

    private static boolean isLoopback(String host) {
        String normalized = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        return "localhost".equalsIgnoreCase(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized);
    }

    private static RealtimeConfigurationException invalidUrl() {
        return new RealtimeConfigurationException(
                "REALTIME_URL_INVALID",
                "app.realtime.supabase-url must be an absolute https URL");
    }

    private static RealtimeConfigurationException invalidPrivateKey(Throwable cause) {
        return new RealtimeConfigurationException(
                "REALTIME_PRIVATE_KEY_INVALID",
                "app.realtime.private-key must be a base64-encoded EC P-256 PKCS#8 private key",
                cause);
    }
}
