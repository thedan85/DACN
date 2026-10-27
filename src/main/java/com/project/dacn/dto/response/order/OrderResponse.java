package com.project.dacn.dto.response.order;

import com.project.dacn.common.constant.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(example = """
        {
          "orderId": "ORD_SWAGGER_DEMO",
          "customerId": "CUS_swagger-customer-example-com",
          "customerName": "Swagger Customer",
          "status": "PENDING",
          "total": 30000.00,
          "address": "123 Demo St, HCMC",
          "recipientName": "Swagger Customer",
          "phone": "0901234567",
          "items": [
            {
              "orderId": "ORD_SWAGGER_DEMO",
              "variantId": "ember-tetra-default",
              "sku": "FISH-EMBER-TETRA",
              "quantity": 2,
              "unitPrice": 15000.00,
              "createdAt": "2026-10-27T02:57:14.486697Z"
            }
          ],
          "createdAt": "2026-10-27T02:57:14.486697Z",
          "updatedAt": "2026-10-27T03:02:02.122872Z"
        }
        """)
public record OrderResponse(
        String orderId,
        String customerId,
        String customerName,
        OrderStatus status,
        BigDecimal total,
        String address,
        String recipientName,
        String phone,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt) {
}
