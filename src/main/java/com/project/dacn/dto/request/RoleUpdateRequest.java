package com.project.dacn.dto.request;

public record RoleUpdateRequest(
        String name,
        String description,
        Boolean system,
        Boolean deleted) {
}
