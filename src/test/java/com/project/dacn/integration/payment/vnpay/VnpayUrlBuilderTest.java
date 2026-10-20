package com.project.dacn.integration.payment.vnpay;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        assertThat(url).contains("vnp_OrderInfo=Thanh%20toan%20don%20hang");

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
