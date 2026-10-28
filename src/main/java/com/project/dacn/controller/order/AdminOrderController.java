package com.project.dacn.controller.order;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.security.Permissions;
import com.project.dacn.service.order.OrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
@Tag(name = "admin-orders")
public class AdminOrderController {

    private final OrderService service;

    /**
     * Admin override: mark an order completed without the shipping flow.
     * PENDING, CONFIRMED and SHIPPING orders are eligible.
     */
    @PostMapping("/{orderId}/complete")
    @PreAuthorize("hasAuthority('" + Permissions.ORDER_COMPLETE + "')")
    public ApiResponse<OrderResponse> complete(@PathVariable String orderId) {
        return ApiResponse.ok(service.completeByAdmin(orderId));
    }
}
