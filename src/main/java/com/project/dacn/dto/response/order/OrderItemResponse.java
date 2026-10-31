package com.project.dacn.dto.response.order;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(example = """
        {
          "orderId": "ORD_SWAGGER_DEMO",
          "variantId": "ember-tetra-default",
          "sku": "FISH-EMBER-TETRA",
          "quantity": 2,
          "unitPrice": 15000.00,
          "createdAt": "2026-10-27T02:57:14.486697Z",
          "productName": "Ember Tetra",
          "thumbnail": "https://res.cloudinary.com/demo/image/upload/ember_tetra.webp",
          "attributes": ["Blue"]
        }
        """)
public record OrderItemResponse(
        String orderId,
        String variantId,
        String sku,
        Integer quantity,
        BigDecimal unitPrice,
        Instant createdAt,
        String productName,
        String thumbnail,
        List<String> attributes) {
}
