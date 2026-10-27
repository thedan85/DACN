package com.project.dacn.dto.response.order;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(example = """
        {
          "orderId": "ORD_SWAGGER_DEMO",
          "variantId": "ember-tetra-default",
          "sku": "FISH-EMBER-TETRA",
          "quantity": 2,
          "unitPrice": 15000.00,
          "createdAt": "2026-10-27T02:57:14.486697Z"
        }
        """)
public record OrderItemResponse(
        String orderId,
        String variantId,
        String sku,
        Integer quantity,
        BigDecimal unitPrice,
        Instant createdAt) {
}
