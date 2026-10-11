package com.project.dacn.dto.request;

public record CustomerUpdateRequest(
        String name,
        String address,
        String phone) {
}
