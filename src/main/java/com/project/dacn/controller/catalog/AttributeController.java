package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.AttributeCreateRequest;
import com.project.dacn.dto.request.catalog.AttributeUpdateRequest;
import com.project.dacn.dto.response.catalog.AttributeResponse;
import com.project.dacn.service.catalog.AttributeService;
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
@RequestMapping("/api/v1/attributes")
@RequiredArgsConstructor
public class AttributeController {

    private final AttributeService service;

    @GetMapping
    public ApiResponse<PageResponse<AttributeResponse>> list(@ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    @GetMapping("/{attributeId}")
    public ApiResponse<AttributeResponse> get(@PathVariable String attributeId) {
        return ApiResponse.ok(service.get(attributeId));
    }

    @PostMapping
    public ApiResponse<AttributeResponse> create(@Valid @RequestBody AttributeCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{attributeId}")
    public ApiResponse<AttributeResponse> replace(@PathVariable String attributeId,
            @Valid @RequestBody AttributeUpdateRequest request) {
        return ApiResponse.ok(service.replace(attributeId, request));
    }

    @PatchMapping("/{attributeId}")
    public ApiResponse<AttributeResponse> patch(@PathVariable String attributeId,
            @Valid @RequestBody AttributeUpdateRequest request) {
        return ApiResponse.ok(service.update(attributeId, request));
    }

    @DeleteMapping("/{attributeId}")
    public ApiResponse<Void> delete(@PathVariable String attributeId) {
        service.delete(attributeId);
        return ApiResponse.ok(null);
    }
}
