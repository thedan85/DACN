package com.project.dacn.modules.identity.dto.response;

import com.project.dacn.modules.common.enums.EmployeeStatus;

import java.time.Instant;

public record EmployeeResponse(
        String employeeId,
        String userId,
        String name,
        String address,
        String phone,
        EmployeeStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
