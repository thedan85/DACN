package com.project.dacn.modules.engagement.dto.request;

import jakarta.validation.constraints.NotBlank;

public record NotificationCreateRequest(
        @NotBlank String userId,
        @NotBlank String message) {
}
