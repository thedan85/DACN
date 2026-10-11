package com.project.dacn.modules.order.dto.response;

import com.project.dacn.modules.common.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        String orderId,
        String customerId,
        String customerName,
        OrderStatus status,
        BigDecimal total,
        String address,
        String recipientName,
        String phone,
        Instant createdAt,
        Instant updatedAt) {
}
