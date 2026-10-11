package com.project.dacn.modules.catalog.dto.response;

import java.time.Instant;

public record MediaResponse(
        String mediaId,
        String url,
        String productId,
        String variantId,
        Boolean deleted,
        Instant createdAt,
        Instant updatedAt) {
}
