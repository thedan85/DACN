package com.project.dacn.dto.response.auth;

import com.project.dacn.common.constant.UserStatus;

import java.time.Instant;

public record AdminUserResponse(
        String userId,
        String email,
        String name,
        String phone,
        String roleCode,
        String roleName,
        UserStatus status,
        Instant lastLogin,
        Instant createdAt) {
}
