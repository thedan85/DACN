package com.project.dacn.dto.request.identity;

public record CustomerUpdateRequest(
        String name,
        String address,
        String phone) {
}
