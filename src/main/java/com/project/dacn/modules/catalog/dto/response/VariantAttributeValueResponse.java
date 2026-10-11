package com.project.dacn.modules.catalog.dto.response;

import java.time.Instant;

public record VariantAttributeValueResponse(
        String variantValueId,
        String variantId,
        String attributeId,
        String attributeName,
        String attributeValueId,
        String attributeValue,
        Instant createdAt) {
}
