package com.project.dacn.dto.response.auth;

import com.project.dacn.common.constant.UserStatus;

import java.time.Instant;
import java.util.List;

public record AdminUserDetailResponse(
        String userId,
        String email,
        String name,
        String phone,
        String address,
        String roleCode,
        String roleName,
        UserStatus status,
        Instant lastLogin,
        Instant createdAt,
        List<String> permissions) {
}
