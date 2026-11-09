package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.CategoryCreateRequest;
import com.project.dacn.dto.request.catalog.CategoryUpdateRequest;
import com.project.dacn.dto.response.catalog.CategoryResponse;
import com.project.dacn.security.Permissions;
import com.project.dacn.service.catalog.CategoryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private static final String PUBLIC_CACHE = "public, max-age=300, stale-while-revalidate=600";

    private final CategoryService service;

    @GetMapping
    public ApiResponse<PageResponse<CategoryResponse>> list(
            @ParameterObject Pageable pageable, HttpServletResponse response) {
        PageResponse<CategoryResponse> result = service.list(pageable);
        response.setHeader(HttpHeaders.CACHE_CONTROL, PUBLIC_CACHE);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{categoryId}")
    public ApiResponse<CategoryResponse> get(@PathVariable String categoryId,
            HttpServletResponse response) {
        CategoryResponse result = service.get(categoryId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, PUBLIC_CACHE);
        return ApiResponse.ok(result);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.CATEGORY_CREATE + "')")
    public ApiResponse<CategoryResponse> create(@Valid @RequestBody CategoryCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{categoryId}")
    @PreAuthorize("hasAuthority('" + Permissions.CATEGORY_UPDATE + "')")
    public ApiResponse<CategoryResponse> replace(@PathVariable String categoryId,
            @Valid @RequestBody CategoryUpdateRequest request) {
        return ApiResponse.ok(service.replace(categoryId, request));
    }

    @PatchMapping("/{categoryId}")
    @PreAuthorize("hasAuthority('" + Permissions.CATEGORY_UPDATE + "')")
    public ApiResponse<CategoryResponse> patch(@PathVariable String categoryId,
            @Valid @RequestBody CategoryUpdateRequest request) {
        return ApiResponse.ok(service.update(categoryId, request));
    }

    @DeleteMapping("/{categoryId}")
    @PreAuthorize("hasAuthority('" + Permissions.CATEGORY_DELETE + "')")
    public ApiResponse<Void> delete(@PathVariable String categoryId) {
        service.delete(categoryId);
        return ApiResponse.ok(null);
    }
}
