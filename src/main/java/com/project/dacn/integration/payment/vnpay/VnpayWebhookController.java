package com.project.dacn.integration.payment.vnpay;

import com.project.dacn.common.response.ApiResponse;
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

    /**
     * VNPay IPN. Response shape is fixed by VNPay: RspCode 00 = received,
     * 97 = bad checksum, 99 = other error.
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
        log.info("VNPay IPN txn {} response {} amount {}",
                params.get("vnp_TxnRef"), params.get("vnp_ResponseCode"), params.get("vnp_Amount"));
        // TODO: mark Payment CONFIRMED / FAILED idempotently on vnp_TransactionNo.
        return Map.of("RspCode", "00", "Message", "Confirm Success");
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
