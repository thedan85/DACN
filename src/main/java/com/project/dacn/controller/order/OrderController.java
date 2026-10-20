package com.project.dacn.controller.order;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.service.order.OrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "orders")
public class OrderController {

    private final OrderService service;

    @GetMapping
    public ApiResponse<List<OrderResponse>> list(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            Authentication auth) {
        return ApiResponse.ok(service.list(resolveCustomerId(auth, customerId)));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> get(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            Authentication auth,
            @PathVariable String orderId) {
        return ApiResponse.ok(service.get(resolveCustomerId(auth, customerId), orderId));
    }

    private Long resolveCustomerId(Authentication auth, Long header) {
        // TODO: JWT — resolve the customer DB id from the authenticated principal via JwtService once it lands.
        if (auth != null && auth.getName() != null && auth.getName().matches("\\d+")) {
            return Long.valueOf(auth.getName());
        }
        if (header == null) {
            throw new IllegalArgumentException("X-Customer-Id is required");
        }
        return header;
    }
}
