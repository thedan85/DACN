package com.project.dacn.modules.auth.dto.response;

import com.project.dacn.modules.common.enums.UserStatus;

import java.time.Instant;

public record UserResponse(
        String userId,
        String email,
        UserStatus status,
        String roleId,
        String roleName,
        Instant lastLogin,
        Instant createdAt,
        Instant updatedAt) {
}
