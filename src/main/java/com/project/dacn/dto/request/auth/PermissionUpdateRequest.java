package com.project.dacn.dto.request.auth;

public record PermissionUpdateRequest(
        String name,
        String resource,
        String action,
        String description) {
}
