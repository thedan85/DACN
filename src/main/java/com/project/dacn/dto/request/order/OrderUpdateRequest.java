package com.project.dacn.dto.request.order;

import com.project.dacn.common.constant.OrderStatus;

public record OrderUpdateRequest(
        OrderStatus status,
        String address,
        String recipientName,
        String phone) {
}
