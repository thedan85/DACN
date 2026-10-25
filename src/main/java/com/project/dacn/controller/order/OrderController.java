package com.project.dacn.controller.order;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.security.CustomerIdentity;
import com.project.dacn.service.order.OrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "orders")
public class OrderController {

    private final OrderService service;
    private final CustomerIdentity identity;

    @GetMapping
    public ApiResponse<List<OrderResponse>> list(Authentication auth) {
        return ApiResponse.ok(service.list(identity.requireCustomerId(auth)));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> get(Authentication auth, @PathVariable String orderId) {
        return ApiResponse.ok(service.get(identity.requireCustomerId(auth), orderId));
    }
}
