package com.project.dacn.dto.response.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderItemResponse(
        Long orderItemId,
        String orderId,
        String variantId,
        String sku,
        Integer quantity,
        BigDecimal unitPrice,
        Instant createdAt) {
}
