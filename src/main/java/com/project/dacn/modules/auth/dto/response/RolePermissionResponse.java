package com.project.dacn.modules.auth.dto.response;

import java.time.Instant;

public record RolePermissionResponse(
        String rolePermissionId,
        String roleId,
        String roleName,
        String permissionId,
        String permissionCode,
        Instant grantedAt) {
}
