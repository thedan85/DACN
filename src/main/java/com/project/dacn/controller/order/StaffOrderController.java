package com.project.dacn.controller.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.order.StaffOrderStatusUpdateRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.service.order.OrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff/orders")
@RequiredArgsConstructor
@Tag(name = "staff-orders")
public class StaffOrderController {

    private final OrderService service;

    @GetMapping
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<PageResponse<OrderResponse>> list(
            @RequestParam(required = false) OrderStatus status,
            @ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.listForStaff(status, pageable));
    }

    /**
     * Accept ({"status":"CONFIRMED"}) or reject ({"status":"CANCELLED"}) a
     * pending order.
     */
    @PatchMapping("/{orderId}/status")
    @PreAuthorize("hasAuthority('order:update')")
    public ApiResponse<OrderResponse> updateStatus(
            @PathVariable String orderId,
            @Valid @RequestBody StaffOrderStatusUpdateRequest request) {
        return ApiResponse.ok(service.updateStatusByStaff(orderId, request));
    }
}
