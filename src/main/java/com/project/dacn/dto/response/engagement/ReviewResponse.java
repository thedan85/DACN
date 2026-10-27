package com.project.dacn.dto.response.engagement;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * customerId is staff-only (admins/employees); it is omitted from the
 * storefront payload, which shows the customer name instead.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewResponse(
                String reviewId,
                String variantId,
                String customerId,
                String customerName,
                boolean verifiedPurchase,
                Integer rating,
                String comment,
                Instant createdAt,
                Instant updatedAt) {
}
