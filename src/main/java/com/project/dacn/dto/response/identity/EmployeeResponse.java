package com.project.dacn.dto.response.identity;

import com.project.dacn.common.constant.EmployeeStatus;

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
