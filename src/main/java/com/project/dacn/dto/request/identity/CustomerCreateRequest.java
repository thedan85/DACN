package com.project.dacn.dto.request.identity;

import jakarta.validation.constraints.NotBlank;

public record CustomerCreateRequest(
        @NotBlank String userId,
        @NotBlank String name,
        String address,
        @NotBlank String phone) {
}
