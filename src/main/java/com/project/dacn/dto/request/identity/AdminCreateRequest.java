package com.project.dacn.dto.request.identity;

import jakarta.validation.constraints.NotBlank;

public record AdminCreateRequest(
        @NotBlank String userId,
        @NotBlank String name) {
}
