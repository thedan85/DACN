package com.project.dacn.integration.payment.vnpay;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class VnpaySignatureService {

    private static final String HMAC_SHA512 = "HmacSHA512";

    private final VnpayProperties properties;

    public VnpaySignatureService(VnpayProperties properties) {
        this.properties = properties;
    }

    /**
     * Canonical string VNPay signs: every non-blank vnp_ param except the hash
     * fields, sorted by name, URL-encoded with spaces as '+' (VNPay's
     * URLEncoder format).
     */
    public String canonical(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(e -> e.getKey().startsWith("vnp_")
                        && !"vnp_SecureHash".equals(e.getKey())
                        && !"vnp_SecureHashType".equals(e.getKey()))
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    public String sign(String rawData) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA512);
            mac.init(new SecretKeySpec(
                    properties.getHashSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA512));
            return HexFormat.of().formatHex(mac.doFinal(rawData.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new VnpayException("cannot sign VNPay payload", e);
        }
    }

    public boolean verify(String rawData, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(rawData).getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    public boolean verifyParams(Map<String, String> params) {
        return verify(canonical(params), params.get("vnp_SecureHash"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
