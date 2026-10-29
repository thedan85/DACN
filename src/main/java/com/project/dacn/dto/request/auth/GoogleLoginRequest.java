package com.project.dacn.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleLoginRequest(
        @NotBlank(message = "idToken is required")
        @Size(max = 8192, message = "idToken is too long")
        @Schema(example = "eyJhbGciOiJSUzI1NiIs...")
        String idToken
) {
}
