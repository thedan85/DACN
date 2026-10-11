package com.project.dacn.dto.response;

import java.time.Instant;

public record AttributeResponse(
        String attributeId,
        String name,
        Boolean deleted,
        Instant createdAt,
        Instant updatedAt) {
}
