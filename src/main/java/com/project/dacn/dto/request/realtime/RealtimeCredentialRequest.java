package com.project.dacn.dto.request.realtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RealtimeCredentialRequest(
        @NotNull List<@NotBlank @Size(max = 255) String> productIds) {
}
