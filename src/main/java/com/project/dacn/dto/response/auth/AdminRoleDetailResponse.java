package com.project.dacn.dto.response.auth;

import java.time.Instant;
import java.util.List;

public record AdminRoleDetailResponse(
        String roleId,
        String code,
        String name,
        String description,
        boolean system,
        Instant createdAt,
        Instant updatedAt,
        List<PermissionResponse> permissions) {
}
