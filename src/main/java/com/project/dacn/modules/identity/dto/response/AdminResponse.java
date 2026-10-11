package com.project.dacn.modules.identity.dto.response;

import com.project.dacn.modules.common.enums.AdminStatus;

import java.time.Instant;

public record AdminResponse(
        String adminId,
        String userId,
        String name,
        AdminStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
