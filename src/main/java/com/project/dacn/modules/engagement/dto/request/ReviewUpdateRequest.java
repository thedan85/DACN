package com.project.dacn.modules.engagement.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ReviewUpdateRequest(
        @Min(1) @Max(5) Integer rating,
        String comment) {
}
