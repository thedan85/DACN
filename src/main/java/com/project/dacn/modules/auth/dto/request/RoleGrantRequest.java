package com.project.dacn.modules.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RoleGrantRequest(
        @NotBlank String roleId,
        @NotBlank String permissionId) {
}
