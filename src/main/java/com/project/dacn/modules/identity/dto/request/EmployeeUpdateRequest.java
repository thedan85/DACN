package com.project.dacn.modules.identity.dto.request;

import com.project.dacn.modules.common.enums.EmployeeStatus;

public record EmployeeUpdateRequest(
        String name,
        String address,
        String phone,
        EmployeeStatus status) {
}
