package com.project.dacn.modules.identity.dto.request;

import com.project.dacn.modules.common.enums.AdminStatus;

public record AdminUpdateRequest(
        String name,
        AdminStatus status) {
}
