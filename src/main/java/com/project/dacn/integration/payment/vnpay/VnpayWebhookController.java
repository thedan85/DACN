package com.project.dacn.integration.payment.vnpay;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.integration.payment.PaymentSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/vnpay")
@RequiredArgsConstructor
@Slf4j
public class VnpayWebhookController {

    private static final String SUCCESS_CODE = "00";

    private final VnpaySignatureService signatureService;
    private final PaymentSettlementService settlementService;
    private final VnpayProperties properties;

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
        String configuredTmn = properties.getTmnCode();
        if (configuredTmn != null && !configuredTmn.isBlank()
                && !configuredTmn.equals(params.get("vnp_TmnCode"))) {
            log.warn("VNPay IPN tmn-code mismatch for txn {}", params.get("vnp_TxnRef"));
            return Map.of("RspCode", "97", "Message", "Invalid tmn code");
        }

        String orderId = params.get("vnp_TxnRef");
        boolean success = SUCCESS_CODE.equals(params.get("vnp_ResponseCode"));
        BigDecimal amount;
        try {
            amount = new BigDecimal(params.get("vnp_Amount")).movePointLeft(2);
        } catch (NumberFormatException | NullPointerException e) {
            return Map.of("RspCode", "99", "Message", "Invalid amount");
        }

        var result = settlementService.settle(
                orderId, amount, success, params.get("vnp_TransactionNo"));
        log.info("VNPay IPN txn {} response {} settlement {}",
                orderId, params.get("vnp_ResponseCode"), result);

        return switch (result) {
            case NOT_FOUND -> Map.of("RspCode", "01", "Message", "Order not found");
            case AMOUNT_MISMATCH -> Map.of("RspCode", "04", "Message", "Invalid amount");
            case REFUND_REQUIRED -> Map.of("RspCode", "00", "Message", "Refund required");
            case SETTLED, ALREADY_SETTLED ->
                    Map.of("RspCode", "00", "Message", "Confirm Success");
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
