package com.project.dacn.dto.response.auth;

import java.time.Instant;

public record RolePermissionResponse(
        String rolePermissionId,
        String roleId,
        String roleName,
        String permissionId,
        String permissionCode,
        Instant grantedAt) {
}
