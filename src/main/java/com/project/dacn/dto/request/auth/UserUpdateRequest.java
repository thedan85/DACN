package com.project.dacn.dto.request.auth;

import com.project.dacn.common.constant.UserStatus;

public record UserUpdateRequest(
        String email,
        UserStatus status) {
}
