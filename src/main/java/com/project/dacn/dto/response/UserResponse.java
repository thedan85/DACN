package com.project.dacn.dto.response;

import com.project.dacn.common.constant.UserStatus;

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
