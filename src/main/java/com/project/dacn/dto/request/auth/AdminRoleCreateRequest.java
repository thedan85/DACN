package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminRoleCreateRequest(
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,49}$") String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description) {
}
