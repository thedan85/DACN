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

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/webhooks/vnpay")
@RequiredArgsConstructor
@Slf4j
public class VnpayWebhookController {

    private static final String SUCCESS_CODE = "00";
    private static final Set<String> FAILURE_CODES = Set.of("09", "10", "11", "12", "13", "24", "51", "65", "75", "79");

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
        if (!validMerchant(params)) {
            return Map.of("RspCode", "99", "Message", "Invalid merchant");
        }
        if (!validReferences(params)) {
            return Map.of("RspCode", "99", "Message", "Invalid payment reference");
        }
        if (!validOutcome(params)) {
            return Map.of("RspCode", "99", "Message", "Invalid payment outcome");
        }
        return switch (paymentService.handleIpn(params)) {
            case CONFIRMED, ALREADY_CONFIRMED, FAILED, ALREADY_FAILED, ALREADY_SETTLED ->
                    Map.of("RspCode", "00", "Message", "Confirm Success");
            case REFUND_REQUIRED -> Map.of("RspCode", "00", "Message", "Received; refund review required");
            case ORDER_NOT_FOUND -> Map.of("RspCode", "01", "Message", "Order not found");
            case AMOUNT_MISMATCH -> Map.of("RspCode", "04", "Message", "Invalid amount");
        };
    }

    /**
     * Browser redirect after payment. Signed outcomes are display hints only;
     * only the IPN may settle. Resolve order identity read-only for the storefront.
     */
    @GetMapping("/return")
    public ResponseEntity<Void> returnPage(@RequestParam Map<String, String> params) {
        boolean valid = signatureService.verifyParams(params) && validMerchant(params)
                && validReferences(params) && validOutcome(params) && validAmountSyntax(params);
        String orderId = valid ? paymentService.orderIdForPayment(params.get("vnp_TxnRef")) : null;

        String outcome = !valid ? "invalid" : successfulOutcome(params) ? "success" : "failed";
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

    /** Matches IPN amount syntax only; it does not prove equality with the persisted payment amount. */
    private static boolean validAmountSyntax(Map<String, String> params) {
        try {
            new BigDecimal(params.get("vnp_Amount")).toBigIntegerExact();
            return true;
        } catch (ArithmeticException | NumberFormatException | NullPointerException invalid) {
            return false;
        }
    }

    private boolean validMerchant(Map<String, String> params) {
        String merchant = properties.getTmnCode();
        return merchant != null && !merchant.isBlank() && merchant.equals(params.get("vnp_TmnCode"));
    }

    private static boolean validReferences(Map<String, String> params) {
        String paymentId = params.get("vnp_TxnRef");
        String transactionId = params.get("vnp_TransactionNo");
        return paymentId != null && !paymentId.isBlank() && transactionId != null && !transactionId.isBlank();
    }

    private static boolean validOutcome(Map<String, String> params) {
        String response = params.get("vnp_ResponseCode");
        return successfulOutcome(params) || ("02".equals(params.get("vnp_TransactionStatus"))
                && response != null && FAILURE_CODES.contains(response));
    }

    private static boolean successfulOutcome(Map<String, String> params) {
        return SUCCESS_CODE.equals(params.get("vnp_ResponseCode"))
                && SUCCESS_CODE.equals(params.get("vnp_TransactionStatus"));
    }
}
