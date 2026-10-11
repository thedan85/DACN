package com.project.dacn.modules.catalog.dto.response;

import java.time.Instant;

public record AttributeResponse(
        String attributeId,
        String name,
        Boolean deleted,
        Instant createdAt,
        Instant updatedAt) {
}
