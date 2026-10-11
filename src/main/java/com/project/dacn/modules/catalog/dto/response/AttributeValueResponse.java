package com.project.dacn.modules.catalog.dto.response;

import java.time.Instant;

public record AttributeValueResponse(
        String attributeValueId,
        String attributeId,
        String attributeName,
        String value,
        Instant createdAt,
        Instant updatedAt) {
}
