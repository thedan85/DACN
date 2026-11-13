package com.project.dacn.integration.payment.vnpay;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VnpayUrlBuilderTest {

    private static final VnpayProperties PROPS = props();

    private static final VnpaySignatureService SIGNATURE = new VnpaySignatureService(PROPS);

    private final VnpayUrlBuilder builder = new VnpayUrlBuilder(PROPS, SIGNATURE);

    @Test
    void buildsSignedUrlWithAmountInVndCents() {
        String url = builder.build("ORD_abc", 150000L, "Thanh toan don hang", "127.0.0.1");

        assertThat(url).startsWith(PROPS.getPayUrl() + "?");
        assertThat(url).contains("vnp_Amount=15000000");
        assertThat(url).contains("vnp_TxnRef=ORD_abc");
        assertThat(url).contains("vnp_OrderInfo=Thanh+toan+don+hang");

        Map<String, String> params = queryParams(url);
        assertThat(params).containsEntry("vnp_TmnCode", "TMN01")
                .containsEntry("vnp_ReturnUrl", PROPS.getReturnUrl())
                .containsEntry("vnp_Version", "2.1.0");
        assertThat(params.get("vnp_CreateDate")).matches("\\d{14}");
        assertThat(params.get("vnp_ExpireDate")).matches("\\d{14}");
    }

    @Test
    void secureHashCoversEveryOtherParam() {
        String url = builder.build("ORD_abc", 150000L, "Thanh toan don hang", "127.0.0.1");
        Map<String, String> params = queryParams(url);
        String secureHash = params.remove("vnp_SecureHash");

        assertThat(secureHash).matches("[0-9a-f]{128}");
        assertThat(SIGNATURE.verify(SIGNATURE.canonical(params), secureHash)).isTrue();
    }

    @Test
    void absoluteExpiryKeepsCutoffAndConvertsAcrossVietnamMidnight() {
        Map<String, String> params = queryParams(builder.build("PAY_absolute", 150000L,
                "Thanh toán đơn hàng & repeat", "127.0.0.1", Instant.parse("2026-11-13T17:01:02Z")));
        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertThat(params).containsEntry("vnp_ExpireDate", "20261114000102")
                .containsEntry("vnp_TxnRef", "PAY_absolute").containsEntry("vnp_Amount", "15000000")
                .containsEntry("vnp_OrderInfo", "Thanh toán đơn hàng & repeat");
        params.put("vnp_ExpireDate", "20261114001602");
        assertThat(SIGNATURE.verifyParams(params)).isFalse();
    }

    @Test
    void subsecondDeadlineTruncatesWithoutRoundingOrCarry() {
        assertSubsecondExpiry("2026-11-13T17:01:02.999Z", "20261114000102");
        assertSubsecondExpiry("2026-11-13T17:01:59.999Z", "20261114000159");
    }

    private void assertSubsecondExpiry(String expiresAt, String expectedProviderTime) {
        Map<String, String> params = queryParams(builder.build("PAY_subsecond", 150000L,
                "subsecond", "127.0.0.1", Instant.parse(expiresAt)));
        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertThat(params).containsEntry("vnp_ExpireDate", expectedProviderTime)
                .containsEntry("vnp_TxnRef", "PAY_subsecond")
                .containsEntry("vnp_Amount", "15000000");
    }

    @Test
    void oldOverloadRetainsItsFifteenMinuteIssuanceWindow() {
        Map<String, String> params = queryParams(builder.build("PAY_legacy", 30L, "legacy", "127.0.0.1"));
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        assertThat(LocalDateTime.parse(params.get("vnp_ExpireDate"), format))
                .isEqualTo(LocalDateTime.parse(params.get("vnp_CreateDate"), format).plusMinutes(15));
        assertThat(SIGNATURE.verifyParams(params)).isTrue();
    }

    private static Map<String, String> queryParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int split = pair.indexOf('=');
            params.put(pair.substring(0, split),
                    URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static VnpayProperties props() {
        VnpayProperties properties = new VnpayProperties();
        properties.setTmnCode("TMN01");
        properties.setHashSecret("SECRETKEY");
        properties.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        properties.setReturnUrl("http://localhost:8080/api/v1/webhooks/vnpay/return");
        properties.setIpnUrl("http://localhost:8080/api/v1/webhooks/vnpay/ipn");
        properties.setVersion("2.1.0");
        properties.setCommand("pay");
        properties.setCurrCode("VND");
        properties.setOrderType("other");
        properties.setLocale("vn");
        return properties;
    }
}
