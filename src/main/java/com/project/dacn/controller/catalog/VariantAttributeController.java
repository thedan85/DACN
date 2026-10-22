package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.VariantAttributeValueCreateRequest;
import com.project.dacn.dto.response.catalog.VariantAttributeValueResponse;
import com.project.dacn.service.catalog.VariantAttributeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class VariantAttributeController {

    private final VariantAttributeService service;

    @GetMapping("/api/v1/variants/{variantId}/attributes")
    public ApiResponse<PageResponse<VariantAttributeValueResponse>> listByVariant(
            @PathVariable String variantId, Pageable pageable) {
        return ApiResponse.ok(service.listByVariant(variantId, pageable));
    }

    @GetMapping("/api/v1/variant-attributes/{variantValueId}")
    public ApiResponse<VariantAttributeValueResponse> get(@PathVariable String variantValueId) {
        return ApiResponse.ok(service.get(variantValueId));
    }

    @PostMapping("/api/v1/variant-attributes")
    @PreAuthorize("hasAuthority('variant:create')")
    public ApiResponse<VariantAttributeValueResponse> create(
            @Valid @RequestBody VariantAttributeValueCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @DeleteMapping("/api/v1/variant-attributes/{variantValueId}")
    @PreAuthorize("hasAuthority('variant:delete')")
    public ApiResponse<Void> delete(@PathVariable String variantValueId) {
        service.delete(variantValueId);
        return ApiResponse.ok(null);
    }
}
