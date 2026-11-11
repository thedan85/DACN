package com.project.dacn.integration.shipping.ghn;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.integration.shipping.ghn.dto.GhnWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/webhooks/ghn")
@RequiredArgsConstructor
@Slf4j
public class GhnWebhookController {

    private final GhnWebhookService service;
    private final GhnProperties properties;

    @PostMapping
    public ResponseEntity<ApiResponse<?>> handle(
            @RequestHeader(value = "X-GHN-Webhook-Token", required = false) String token,
            @RequestBody GhnWebhookPayload payload) {
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.error("GHN webhook secret is not configured");
            return ResponseEntity.status(503).body(ApiResponse.of(503, "webhook disabled", null));
        }
        if (token == null
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected GHN webhook with an invalid token");
            return ResponseEntity.status(401).body(ApiResponse.of(401, "unauthorized", null));
        }
        try {
            service.handle(payload);
            return ResponseEntity.ok(ApiResponse.of(200, "OK", null));
        } catch (DataIntegrityViolationException ex) {
            log.error("GHN webhook database conflict", ex);
            return ResponseEntity.status(500).body(ApiResponse.of(500, "webhook failed", null));
        }
    }
}
