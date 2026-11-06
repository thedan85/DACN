package com.project.dacn.security;

import com.project.dacn.integration.realtime.RealtimeProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class TrustedClientIpResolver {

    private static final String IP_LITERAL = "[0-9A-Fa-f:.]+";

    private final RealtimeProperties properties;

    public String resolve(HttpServletRequest request) {
        String header = properties.getTrustedClientIpHeader();
        if (header == null || header.isBlank()) {
            return request.getRemoteAddr();
        }
        String value = request.getHeader(header);
        if (value == null || value.isBlank() || value.length() > 64
                || !value.matches(IP_LITERAL)) {
            return request.getRemoteAddr();
        }
        if (value.indexOf(':') < 0) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4 || Arrays.stream(octets).anyMatch(
                    octet -> !octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255)) {
                return request.getRemoteAddr();
            }
        } else {
            try {
                // Colon-containing, allowlisted input is parsed as an IPv6 literal, never a DNS name.
                InetAddress.getByName(value);
            } catch (UnknownHostException ex) {
                return request.getRemoteAddr();
            }
        }
        return value;
    }
}
