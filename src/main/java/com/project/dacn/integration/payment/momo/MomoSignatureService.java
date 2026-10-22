package com.project.dacn.integration.payment.momo;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class MomoSignatureService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final MomoProperties properties;

    public MomoSignatureService(MomoProperties properties) {
        this.properties = properties;
    }

    public String sign(String rawData) {
        String secret = properties.getSecretKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("payment.momo.secret-key is not configured");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(rawData.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign MoMo payload", e);
        }
    }

    public boolean verify(String rawData, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String secret = properties.getSecretKey();
        if (secret == null || secret.isBlank()) {
            // Fail closed: an unconfigured secret must never accept callbacks.
            return false;
        }
        return MessageDigest.isEqual(
                sign(rawData).getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
}
