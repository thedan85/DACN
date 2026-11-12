package com.project.dacn.integration.payment.vnpay;

import com.project.dacn.service.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/vnpay")
@RequiredArgsConstructor
@Slf4j
public class VnpayWebhookController {

    private static final String SUCCESS_CODE = "00";

    private final VnpaySignatureService signatureService;
    private final PaymentService paymentService;
    private final VnpayProperties properties;

    /**
     * VNPay IPN. Response shape is fixed by VNPay: 00 = received,
     * 01 = order not found, 04 = invalid amount, 97 = bad checksum,
     * 99 = other error.
     */
    @GetMapping("/ipn")
    public Map<String, String> ipn(@RequestParam Map<String, String> params) {
        if (params.get("vnp_SecureHash") == null) {
            log.warn("VNPay IPN without vnp_SecureHash for txn {}", params.get("vnp_TxnRef"));
            return Map.of("RspCode", "99", "Message", "Missing signature");
        }
        if (!signatureService.verifyParams(params)) {
            log.warn("VNPay IPN signature mismatch for txn {}", params.get("vnp_TxnRef"));
            return Map.of("RspCode", "97", "Message", "Invalid signature");
        }
        return switch (paymentService.handleIpn(params)) {
            case CONFIRMED, ALREADY_CONFIRMED, FAILED, ALREADY_FAILED ->
                    Map.of("RspCode", "00", "Message", "Confirm Success");
            case ORDER_NOT_FOUND -> Map.of("RspCode", "01", "Message", "Order not found");
            case AMOUNT_MISMATCH -> Map.of("RspCode", "04", "Message", "Invalid amount");
        };
    }

    /**
     * Browser redirect after payment. Settles here as well as from the IPN so a
     * missing IPN (for example an unreachable tunnel) cannot leave a paid order
     * pending. Settlement is idempotent, so both callbacks are safe. The browser
     * is then sent back to the storefront order page.
     */
    @GetMapping("/return")
    public ResponseEntity<Void> returnPage(@RequestParam Map<String, String> params) {
        boolean valid = signatureService.verifyParams(params);
        boolean success = SUCCESS_CODE.equals(params.get("vnp_ResponseCode"));
        String txnRef = params.get("vnp_TxnRef");
        String orderId = null;

        if (valid) {
            var result = paymentService.handleIpn(params);
            orderId = paymentService.orderIdForPayment(txnRef);
            log.info("VNPay return txn {} response {} settlement {}",
                    txnRef, params.get("vnp_ResponseCode"), result);
        } else {
            log.warn("VNPay return signature mismatch for txn {}", txnRef);
        }

        String outcome = !valid ? "invalid" : success ? "success" : "failed";
        String base = properties.getFrontendReturnUrl();
        if (base == null || base.isBlank()) {
            base = "/";
        }
        StringBuilder target = new StringBuilder(
                base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        if (orderId != null && !orderId.isBlank()) {
            target.append('/').append(UriUtils.encodePathSegment(orderId, StandardCharsets.UTF_8));
        }
        target.append("?payment=").append(outcome);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(target.toString()))
                .build();
    }
}
