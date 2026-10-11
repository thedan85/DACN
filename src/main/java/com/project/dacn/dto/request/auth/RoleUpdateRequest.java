package com.project.dacn.dto.request.auth;

public record RoleUpdateRequest(
        String name,
        String description,
        Boolean system,
        Boolean deleted) {
}
