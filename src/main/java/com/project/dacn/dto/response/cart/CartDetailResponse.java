package com.project.dacn.dto.response.cart;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CartDetailResponse(
        String cartId,
        Long customerId,
        String customerName,
        List<CartItemResponse> items,
        int totalQuantity,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant updatedAt) {
}
