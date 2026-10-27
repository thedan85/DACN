package com.project.dacn.dto.response.cart;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(example = """
        {
          "cartItemId": "CI_SWAGGER_EMBER",
          "cartId": "CART_CUS_swagger-customer-example-com",
          "variantId": "ember-tetra-default",
          "sku": "FISH-EMBER-TETRA",
          "quantity": 2,
          "price": 15000.00,
          "lineTotal": 30000.00,
          "productId": "PRD_7",
          "productName": "Ember Tetra",
          "thumbnail": "https://res.cloudinary.com/ibk3z2nt/image/upload/v1790922811/ember_tetra.webp",
          "stock": 48,
          "createdAt": "2026-10-27T02:57:14.486697Z",
          "updatedAt": "2026-10-27T02:57:14.486697Z"
        }
        """)
public record CartItemResponse(
        String cartItemId,
        String cartId,
        String variantId,
        String sku,
        Integer quantity,
        BigDecimal price,
        BigDecimal lineTotal,
        String productId,
        String productName,
        String thumbnail,
        Integer stock,
        Instant createdAt,
        Instant updatedAt) {
}
