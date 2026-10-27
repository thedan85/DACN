package com.project.dacn.dto.response.cart;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(example = """
        {
          "cartId": "CART_CUS_swagger-customer-example-com",
          "customerId": "CUS_swagger-customer-example-com",
          "customerName": "Swagger Customer",
          "items": [
            {
              "cartItemId": "CI_SWAGGER_NEON",
              "cartId": "CART_CUS_swagger-customer-example-com",
              "variantId": "neon-tetra-default",
              "sku": "FISH-NEON-TETRA",
              "quantity": 1,
              "price": 8000.00,
              "lineTotal": 8000.00,
              "productId": "PRD_13",
              "productName": "Neon Tetra",
              "thumbnail": "https://res.cloudinary.com/ibk3z2nt/image/upload/v1790779084/neon_tetra.webp",
              "stock": 49,
              "createdAt": "2026-10-27T02:57:14.486697Z",
              "updatedAt": "2026-10-27T02:57:14.486697Z"
            },
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
          ],
          "totalQuantity": 3,
          "totalAmount": 38000.00,
          "createdAt": "2026-10-27T02:57:14.486697Z",
          "updatedAt": "2026-10-27T02:57:14.486697Z"
        }
        """)
public record CartDetailResponse(
        String cartId,
        String customerId,
        String customerName,
        List<CartItemResponse> items,
        int totalQuantity,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant updatedAt) {
}
