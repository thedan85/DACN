package com.project.dacn.modules.catalog.dto.response;

import java.time.Instant;

public record ProductAttributeValueResponse(
        String productValueId,
        String productId,
        String attributeId,
        String attributeName,
        String attributeValueId,
        String attributeValue,
        Instant createdAt) {
}
