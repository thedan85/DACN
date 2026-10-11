package com.project.dacn.modules.identity.dto.request;

public record CustomerUpdateRequest(
        String name,
        String address,
        String phone) {
}
