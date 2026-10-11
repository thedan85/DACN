package com.project.dacn.dto.request.engagement;

import jakarta.validation.constraints.NotBlank;

public record NotificationCreateRequest(
        @NotBlank String userId,
        @NotBlank String message) {
}
