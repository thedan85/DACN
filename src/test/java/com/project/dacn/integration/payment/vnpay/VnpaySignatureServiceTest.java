package com.project.dacn.integration.payment.vnpay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VnpaySignatureServiceTest {

    private static final VnpaySignatureService SERVICE = new VnpaySignatureService(props("Jefe"));

    @Test
    void signsRfc4231VectorAsLowercaseHex() {
        assertThat(SERVICE.sign("what do ya want for nothing?"))
                .isEqualTo("164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554"
                        + "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737");
    }

    @Test
    void verifyRejectsTamperedData() {
        String signature = SERVICE.sign("vnp_Amount=100");
        assertThat(SERVICE.verify("vnp_Amount=100", signature)).isTrue();
        assertThat(SERVICE.verify("vnp_Amount=101", signature)).isFalse();
        assertThat(SERVICE.verify("vnp_Amount=100", null)).isFalse();
    }

    @Test
    void canonicalSortsParamsEncodesValuesAndSkipsHashFields() {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("vnp_TxnRef", "ORD 1");
        params.put("vnp_Amount", "100");
        params.put("vnp_SecureHash", "deadbeef");
        params.put("vnp_Empty", "");
        params.put("vnp_SecureHashType", "HmacSHA512");

        assertThat(SERVICE.canonical(params))
                .isEqualTo("vnp_Amount=100&vnp_TxnRef=ORD+1");
    }

    private static VnpayProperties props(String secret) {
        VnpayProperties properties = new VnpayProperties();
        properties.setHashSecret(secret);
        return properties;
    }
}
