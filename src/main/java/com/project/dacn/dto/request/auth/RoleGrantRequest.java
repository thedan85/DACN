package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.NotBlank;

public record RoleGrantRequest(
        @NotBlank String roleId,
        @NotBlank String permissionId) {
}
