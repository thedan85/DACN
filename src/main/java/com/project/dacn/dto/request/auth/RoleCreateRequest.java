package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RoleCreateRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank String name,
        String description,
        Boolean system) {
}
