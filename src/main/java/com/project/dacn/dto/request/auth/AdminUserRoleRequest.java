package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.NotBlank;

public record AdminUserRoleRequest(
        @NotBlank(message = "roleCode is required") String roleCode) {
}
