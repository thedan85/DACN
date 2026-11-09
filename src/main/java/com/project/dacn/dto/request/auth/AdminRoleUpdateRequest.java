package com.project.dacn.dto.request.auth;

import jakarta.validation.constraints.Size;

public record AdminRoleUpdateRequest(
        @Size(max = 255) String name,
        @Size(max = 2000) String description) {
}
