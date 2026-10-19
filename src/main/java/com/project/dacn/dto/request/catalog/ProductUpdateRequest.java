package com.project.dacn.dto.request.catalog;

import jakarta.validation.constraints.Size;

public record ProductUpdateRequest(
        @Size(max = 200) String name,
        String description,
        String detailDescription,
        String thumbnail,
        String categoryId) {
}
