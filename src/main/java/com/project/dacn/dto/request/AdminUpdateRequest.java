package com.project.dacn.dto.request;

import com.project.dacn.common.constant.AdminStatus;

public record AdminUpdateRequest(
        String name,
        AdminStatus status) {
}
