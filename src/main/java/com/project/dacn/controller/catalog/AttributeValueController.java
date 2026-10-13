package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.AttributeValueCreateRequest;
import com.project.dacn.dto.request.catalog.AttributeValueUpdateRequest;
import com.project.dacn.dto.response.catalog.AttributeValueResponse;
import com.project.dacn.service.catalog.AttributeValueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AttributeValueController {

    private final AttributeValueService service;

    @GetMapping("/api/attribute-values")
    public ApiResponse<PageResponse<AttributeValueResponse>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    @GetMapping("/api/attribute-values/{attributeValueId}")
    public ApiResponse<AttributeValueResponse> get(@PathVariable String attributeValueId) {
        return ApiResponse.ok(service.get(attributeValueId));
    }

    @GetMapping("/api/attributes/{attributeId}/values")
    public ApiResponse<PageResponse<AttributeValueResponse>> listByAttribute(
            @PathVariable String attributeId, Pageable pageable) {
        return ApiResponse.ok(service.listByAttribute(attributeId, pageable));
    }

    @PostMapping("/api/attribute-values")
    public ApiResponse<AttributeValueResponse> create(
            @Valid @RequestBody AttributeValueCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/api/attribute-values/{attributeValueId}")
    public ApiResponse<AttributeValueResponse> replace(@PathVariable String attributeValueId,
            @Valid @RequestBody AttributeValueUpdateRequest request) {
        return ApiResponse.ok(service.update(attributeValueId, request));
    }

    @PatchMapping("/api/attribute-values/{attributeValueId}")
    public ApiResponse<AttributeValueResponse> patch(@PathVariable String attributeValueId,
            @Valid @RequestBody AttributeValueUpdateRequest request) {
        return ApiResponse.ok(service.update(attributeValueId, request));
    }

    @DeleteMapping("/api/attribute-values/{attributeValueId}")
    public ApiResponse<Void> delete(@PathVariable String attributeValueId) {
        service.delete(attributeValueId);
        return ApiResponse.ok(null);
    }
}
