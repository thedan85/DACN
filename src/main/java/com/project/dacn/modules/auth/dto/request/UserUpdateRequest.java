package com.project.dacn.modules.auth.dto.request;

import com.project.dacn.modules.common.enums.UserStatus;

public record UserUpdateRequest(
        String email,
        UserStatus status) {
}
