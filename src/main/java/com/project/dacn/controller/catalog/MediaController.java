package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.MediaCreateRequest;
import com.project.dacn.dto.request.catalog.MediaUpdateRequest;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.service.catalog.MediaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MediaController {

    private final MediaService service;

    @GetMapping("/api/media")
    public ApiResponse<PageResponse<MediaResponse>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    @GetMapping("/api/media/{mediaId}")
    public ApiResponse<MediaResponse> get(@PathVariable String mediaId) {
        return ApiResponse.ok(service.get(mediaId));
    }

    @GetMapping("/api/products/{productId}/media")
    public ApiResponse<PageResponse<MediaResponse>> listByProduct(@PathVariable String productId,
            Pageable pageable) {
        return ApiResponse.ok(service.listByProduct(productId, pageable));
    }

    @GetMapping("/api/variants/{variantId}/media")
    public ApiResponse<PageResponse<MediaResponse>> listByVariant(@PathVariable String variantId,
            Pageable pageable) {
        return ApiResponse.ok(service.listByVariant(variantId, pageable));
    }

    @PostMapping("/api/media")
    public ApiResponse<MediaResponse> create(@Valid @RequestBody MediaCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/api/media/{mediaId}")
    public ApiResponse<MediaResponse> replace(@PathVariable String mediaId,
            @Valid @RequestBody MediaUpdateRequest request) {
        return ApiResponse.ok(service.update(mediaId, request));
    }

    @PatchMapping("/api/media/{mediaId}")
    public ApiResponse<MediaResponse> patch(@PathVariable String mediaId,
            @Valid @RequestBody MediaUpdateRequest request) {
        return ApiResponse.ok(service.update(mediaId, request));
    }

    @DeleteMapping("/api/media/{mediaId}")
    public ApiResponse<Void> delete(@PathVariable String mediaId) {
        service.delete(mediaId);
        return ApiResponse.ok(null);
    }
}
