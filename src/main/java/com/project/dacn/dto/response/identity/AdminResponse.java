package com.project.dacn.dto.response.identity;

import com.project.dacn.common.constant.AdminStatus;

import java.time.Instant;

public record AdminResponse(
        String adminId,
        String userId,
        String name,
        AdminStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
