package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.NotBlank;

public record AdminUserStatusRequest(
        @NotBlank(message = "status is required") String status) {
}
