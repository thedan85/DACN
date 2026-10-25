package com.project.dacn.integration.payment.momo;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.integration.payment.PaymentSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/momo")
@RequiredArgsConstructor
@Slf4j
public class MomoWebhookController {

    /**
     * Fields that make up MoMo's IPN signature, in the order MoMo specifies.
     */
    private static final List<String> SIGNED_FIELDS = List.of(
            "accessKey", "amount", "extraData", "message", "orderId", "orderInfo",
            "orderType", "partnerCode", "payType", "requestId", "responseTime",
            "resultCode", "transId");

    private final MomoSignatureService signatureService;
    private final MomoProperties properties;
    private final PaymentSettlementService settlementService;

    @PostMapping
    public ResponseEntity<ApiResponse<String>> handleWebhook(
            @RequestBody Map<String, Object> payload) {
        String signature = stringValue(payload, "signature");
        if (!signatureService.verify(canonical(payload), signature)) {
            log.warn("MoMo IPN signature mismatch for order {}", payload.get("orderId"));
            return ResponseEntity.badRequest()
                    .body(ApiResponse.of(400, "invalid signature", null));
        }

        String orderId = stringValue(payload, "orderId");
        boolean success = "0".equals(stringValue(payload, "resultCode"));
        BigDecimal amount;
        try {
            amount = new BigDecimal(stringValue(payload, "amount"));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.of(400, "invalid amount", null));
        }

        var result = settlementService.settle(
                orderId, amount, success, stringValue(payload, "transId"));
        log.info("MoMo IPN order {} resultCode {} settlement {}",
                orderId, payload.get("resultCode"), result);

        if (result == PaymentSettlementService.Result.NOT_FOUND) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.of(404, "order not found", null));
        }
        // MoMo only needs a 2xx acknowledgement; settlement is idempotent.
        return ResponseEntity.ok(ApiResponse.ok("received"));
    }

    private String canonical(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        for (String field : SIGNED_FIELDS) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            String value = "accessKey".equals(field)
                    ? (properties.getAccessKey() == null ? "" : properties.getAccessKey())
                    : stringValue(payload, field);
            sb.append(field).append('=').append(value);
        }
        return sb.toString();
    }

    private static String stringValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
