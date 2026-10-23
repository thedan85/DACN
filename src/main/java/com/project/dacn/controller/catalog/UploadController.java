package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.response.catalog.UploadResponse;
import com.project.dacn.service.catalog.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService service;

    @PostMapping(value = "/products/{productId}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('media:create')")
    public ApiResponse<UploadResponse> uploadThumbnail(@PathVariable String productId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(service.uploadProductThumbnail(productId, file));
    }

    @PostMapping(value = "/variants/{variantId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('media:create')")
    public ApiResponse<UploadResponse> uploadVariantImage(@PathVariable String variantId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(service.uploadVariantImage(variantId, file));
    }
}
