package com.project.dacn.dto.request.engagement;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ReviewUpdateRequest(
        @Min(1) @Max(5) Integer rating,
        String comment) {
}
