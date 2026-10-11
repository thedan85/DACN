package com.project.dacn.modules.auth.dto.response;

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
