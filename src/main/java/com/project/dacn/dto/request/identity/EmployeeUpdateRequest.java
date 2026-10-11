package com.project.dacn.dto.request.identity;

import com.project.dacn.common.constant.EmployeeStatus;

public record EmployeeUpdateRequest(
        String name,
        String address,
        String phone,
        EmployeeStatus status) {
}
