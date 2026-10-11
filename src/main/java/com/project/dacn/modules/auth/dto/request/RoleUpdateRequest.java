package com.project.dacn.modules.auth.dto.request;

public record RoleUpdateRequest(
        String name,
        String description,
        Boolean system,
        Boolean deleted) {
}
