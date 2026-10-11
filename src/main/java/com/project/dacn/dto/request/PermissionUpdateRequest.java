package com.project.dacn.dto.request;

public record PermissionUpdateRequest(
        String name,
        String resource,
        String action,
        String description) {
}
