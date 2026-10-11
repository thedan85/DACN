package com.project.dacn.modules.auth.dto.request;

public record PermissionUpdateRequest(
        String name,
        String resource,
        String action,
        String description) {
}
