package com.project.dacn.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UserCreateRequest(
        @NotBlank @Email String email,
        @NotBlank String passwordHash,
        @NotBlank String roleId,
        java.time.Instant lastLogin) {
}
