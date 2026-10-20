package com.project.dacn.dto.response.order;

import com.project.dacn.common.constant.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        String orderId,
        Long customerId,
        String customerName,
        OrderStatus status,
        BigDecimal total,
        String address,
        String recipientName,
        String phone,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt) {
}
