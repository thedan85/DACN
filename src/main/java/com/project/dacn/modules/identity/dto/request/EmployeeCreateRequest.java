package com.project.dacn.modules.identity.dto.request;

import jakarta.validation.constraints.NotBlank;

public record EmployeeCreateRequest(
        @NotBlank String userId,
        @NotBlank String name,
        String address,
        @NotBlank String phone) {
}
