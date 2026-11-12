package com.project.dacn.dto.response.auth;

import java.time.Instant;

public record AdminRoleSummaryResponse(
        String roleId,
        String code,
        String name,
        String description,
        boolean system,
        Instant createdAt,
        Instant updatedAt) {
}
