package com.project.dacn.dto.response.auth;

import java.time.Instant;

public record PermissionResponse(
        String permissionId,
        String code,
        String name,
        String resource,
        String action,
        String description,
        Instant createdAt,
        Instant updatedAt) {
}
