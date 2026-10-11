package com.project.dacn.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PermissionCreateRequest(
        @NotBlank @Size(max = 100) String code,
        @NotBlank String name,
        @NotBlank @Size(max = 50) String resource,
        @NotBlank @Size(max = 50) String action,
        String description) {
}
