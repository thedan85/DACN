package com.project.dacn.modules.identity.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminCreateRequest(
        @NotBlank String userId,
        @NotBlank String name) {
}
