package com.project.dacn.dto.request.identity;

import com.project.dacn.common.constant.AdminStatus;

public record AdminUpdateRequest(
        String name,
        AdminStatus status) {
}
