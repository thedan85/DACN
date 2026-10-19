package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.CategoryCreateRequest;
import com.project.dacn.dto.request.catalog.CategoryUpdateRequest;
import com.project.dacn.dto.response.catalog.CategoryResponse;
import com.project.dacn.service.catalog.CategoryService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService service;

    @GetMapping
    public ApiResponse<PageResponse<CategoryResponse>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    @GetMapping("/{categoryId}")
    public ApiResponse<CategoryResponse> get(@PathVariable String categoryId) {
        return ApiResponse.ok(service.get(categoryId));
    }

    @PostMapping
    public ApiResponse<CategoryResponse> create(@Valid @RequestBody CategoryCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{categoryId}")
    public ApiResponse<CategoryResponse> replace(@PathVariable String categoryId,
            @Valid @RequestBody CategoryUpdateRequest request) {
        return ApiResponse.ok(service.replace(categoryId, request));
    }

    @PatchMapping("/{categoryId}")
    public ApiResponse<CategoryResponse> patch(@PathVariable String categoryId,
            @Valid @RequestBody CategoryUpdateRequest request) {
        return ApiResponse.ok(service.update(categoryId, request));
    }

    @DeleteMapping("/{categoryId}")
    public ApiResponse<Void> delete(@PathVariable String categoryId) {
        service.delete(categoryId);
        return ApiResponse.ok(null);
    }
}
