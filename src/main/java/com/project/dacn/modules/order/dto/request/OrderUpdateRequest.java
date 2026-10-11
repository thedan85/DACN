package com.project.dacn.modules.order.dto.request;

import com.project.dacn.modules.common.enums.OrderStatus;

public record OrderUpdateRequest(
        OrderStatus status,
        String address,
        String recipientName,
        String phone) {
}
