package com.project.dacn.controller.payment;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.dto.response.payment.PaymentUrlResponse;
import com.project.dacn.security.CustomerIdentity;
import com.project.dacn.service.payment.PaymentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "payments")
public class PaymentController {

    private final PaymentService service;
    private final CustomerIdentity identity;

    @PostMapping("/vnpay/{orderId}")
    public ApiResponse<PaymentUrlResponse> createVnpayUrl(
            Authentication auth,
            @PathVariable String orderId,
            HttpServletRequest request) {
        return ApiResponse.ok(service.createVnpayUrl(
                identity.requireCustomerId(auth), orderId, clientIp(request)));
    }

    @GetMapping("/orders/{orderId}")
    public ApiResponse<PaymentResponse> get(Authentication auth, @PathVariable String orderId) {
        return ApiResponse.ok(service.get(identity.requireCustomerId(auth), orderId));
    }

    /** VNPay rejects IPv6 loopback; map it so local sandbox runs still pass. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
    }
}
