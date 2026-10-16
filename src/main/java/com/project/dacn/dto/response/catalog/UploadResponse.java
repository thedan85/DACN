package com.project.dacn.dto.response.catalog;

public record UploadResponse(
        String url,
        String folder,
        String productId,
        String variantId,
        String mediaId) {
}
