package com.project.dacn.dto.response.catalog;

import java.time.Instant;

public record AttributeValueResponse(
        String attributeValueId,
        String attributeId,
        String attributeName,
        String value,
        Instant createdAt,
        Instant updatedAt) {
}
