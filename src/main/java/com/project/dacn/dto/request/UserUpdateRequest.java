package com.project.dacn.dto.request;

import com.project.dacn.common.constant.UserStatus;

public record UserUpdateRequest(
        String email,
        UserStatus status) {
}
