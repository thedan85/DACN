package com.project.dacn.security;

import com.project.dacn.integration.realtime.RealtimeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedClientIpResolverTest {

    private static TrustedClientIpResolver resolver(String configuredHeader) {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setTrustedClientIpHeader(configuredHeader);
        return new TrustedClientIpResolver(properties);
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void blankHeaderUsesRemoteAddressAndIgnoresForwardedFor() {
        MockHttpServletRequest request = request("203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("CF-Connecting-IP", "198.51.100.2");

        assertThat(resolver(" ").resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void configuredHeaderIsUsedAndForwardedForIsStillIgnored() {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.9");

        assertThat(resolver("CF-Connecting-IP").resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void blankMalformedOrOversizedHeaderFallsBackToRemoteAddress() {
        MockHttpServletRequest blank = request("10.0.0.2");
        blank.addHeader("CF-Connecting-IP", "   ");
        assertThat(resolver("CF-Connecting-IP").resolve(blank)).isEqualTo("10.0.0.2");

        MockHttpServletRequest malformed = request("10.0.0.3");
        malformed.addHeader("CF-Connecting-IP", "203.0.113.7, 198.51.100.9");
        assertThat(resolver("CF-Connecting-IP").resolve(malformed)).isEqualTo("10.0.0.3");

        MockHttpServletRequest oversized = request("10.0.0.4");
        oversized.addHeader("CF-Connecting-IP", "a".repeat(65));
        assertThat(resolver("CF-Connecting-IP").resolve(oversized)).isEqualTo("10.0.0.4");
    }

    @Test
    void acceptsIpv4AndIpv6Literals() {
        MockHttpServletRequest ipv6 = request("10.0.0.5");
        ipv6.addHeader("CF-Connecting-IP", "2001:db8::1");
        assertThat(resolver("CF-Connecting-IP").resolve(ipv6)).isEqualTo("2001:db8::1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"999.0.0.1", "203.0.113", "deadbeef", "2001:db8:::1", ".", ":"})
    void invalidIpLiteralsFallBackToRemoteAddress(String value) {
        MockHttpServletRequest request = request("10.0.0.6");
        request.addHeader("CF-Connecting-IP", value);

        assertThat(resolver("CF-Connecting-IP").resolve(request)).isEqualTo("10.0.0.6");
    }

    @Test
    void missingConfiguredHeaderFallsBackToRemoteAddress() {
        assertThat(resolver("CF-Connecting-IP").resolve(request("10.0.0.7"))).isEqualTo("10.0.0.7");
    }
}
