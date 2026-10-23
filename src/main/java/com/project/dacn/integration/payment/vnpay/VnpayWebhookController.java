package com.project.dacn.integration.payment.vnpay;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.service.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/vnpay")
@RequiredArgsConstructor
@Slf4j
public class VnpayWebhookController {

    private final VnpaySignatureService signatureService;
    private final PaymentService paymentService;

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
            case ORDER_NOT_FOUND, ORDER_CANCELLED ->
                    Map.of("RspCode", "01", "Message", "Order not found");
            case AMOUNT_MISMATCH -> Map.of("RspCode", "04", "Message", "Invalid amount");
        };
    }

    /**
     * Browser redirect after payment. Shows the verified result; the order is
     * only settled from the IPN above.
     */
    @GetMapping("/return")
    public ApiResponse<Map<String, String>> returnPage(@RequestParam Map<String, String> params) {
        boolean valid = signatureService.verifyParams(params);
        log.info("VNPay return txn {} response {} verified {}",
                params.get("vnp_TxnRef"), params.get("vnp_ResponseCode"), valid);
        return ApiResponse.ok(Map.of(
                "valid", String.valueOf(valid),
                "txnRef", String.valueOf(params.get("vnp_TxnRef")),
                "responseCode", String.valueOf(params.get("vnp_ResponseCode"))));
    }
}
