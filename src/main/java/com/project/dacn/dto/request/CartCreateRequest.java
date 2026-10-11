package com.project.dacn.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CartCreateRequest(
        @NotBlank String customerId) {
}
