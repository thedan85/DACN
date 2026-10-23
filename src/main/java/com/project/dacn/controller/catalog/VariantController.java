package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.VariantCreateRequest;
import com.project.dacn.dto.request.catalog.VariantUpdateRequest;
import com.project.dacn.dto.response.catalog.VariantResponse;
import com.project.dacn.service.catalog.VariantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class VariantController {

    private final VariantService service;

    @GetMapping("/api/v1/variants")
    public ApiResponse<PageResponse<VariantResponse>> list(@ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    @GetMapping("/api/v1/variants/{variantId}")
    public ApiResponse<VariantResponse> get(@PathVariable String variantId) {
        return ApiResponse.ok(service.get(variantId));
    }

    @GetMapping("/api/v1/products/{slug}/variants")
    public ApiResponse<PageResponse<VariantResponse>> listByProduct(@PathVariable String slug,
            @ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.listByProductSlug(slug, pageable));
    }

    @PostMapping("/api/v1/variants")
    public ApiResponse<VariantResponse> create(@Valid @RequestBody VariantCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/api/v1/variants/{variantId}")
    public ApiResponse<VariantResponse> replace(@PathVariable String variantId,
            @Valid @RequestBody VariantUpdateRequest request) {
        return ApiResponse.ok(service.replace(variantId, request));
    }

    @PatchMapping("/api/v1/variants/{variantId}")
    public ApiResponse<VariantResponse> patch(@PathVariable String variantId,
            @Valid @RequestBody VariantUpdateRequest request) {
        return ApiResponse.ok(service.update(variantId, request));
    }

    @DeleteMapping("/api/v1/variants/{variantId}")
    public ApiResponse<Void> delete(@PathVariable String variantId) {
        service.delete(variantId);
        return ApiResponse.ok(null);
    }
}
