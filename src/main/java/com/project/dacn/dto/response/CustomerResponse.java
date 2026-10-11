package com.project.dacn.dto.response;

import java.time.Instant;

public record CustomerResponse(
        String customerId,
        String userId,
        String userEmail,
        String name,
        String address,
        String phone,
        Instant createdAt,
        Instant updatedAt) {
}
