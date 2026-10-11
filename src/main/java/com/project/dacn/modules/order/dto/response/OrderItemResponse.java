package com.project.dacn.modules.order.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderItemResponse(
        String orderItemId,
        String orderId,
        String variantId,
        String sku,
        Integer quantity,
        BigDecimal unitPrice,
        Instant createdAt) {
}
