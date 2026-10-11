package com.project.dacn.dto.response;

import java.time.Instant;

public record RoleResponse(
        String roleId,
        String code,
        String name,
        String description,
        Boolean system,
        Boolean deleted,
        Instant createdAt,
        Instant updatedAt) {
}
