package com.project.dacn.dto.request.catalog;

public record ProductUpdateRequest(
        String name,
        String description,
        String detailDescription,
        String thumbnail,
        String categoryId) {
}
