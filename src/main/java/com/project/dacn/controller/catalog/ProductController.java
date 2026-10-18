package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.request.catalog.ProductUpdateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.service.catalog.ProductService;
import com.project.dacn.spec.ProductFilter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService service;

    @GetMapping
    public ApiResponse<PageResponse<ProductResponse>> list(@ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<ProductResponse>> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String categoryId,
            @ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.search(new ProductFilter(name, status, categoryId), pageable));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductResponse> get(@PathVariable String productId) {
        return ApiResponse.ok(service.get(productId));
    }

    @PostMapping
    public ApiResponse<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{productId}")
    public ApiResponse<ProductResponse> replace(@PathVariable String productId,
            @Valid @RequestBody ProductUpdateRequest request) {
        return ApiResponse.ok(service.replace(productId, request));
    }

    @PatchMapping("/{productId}")
    public ApiResponse<ProductResponse> patch(@PathVariable String productId,
            @Valid @RequestBody ProductUpdateRequest request) {
        return ApiResponse.ok(service.update(productId, request));
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Void> delete(@PathVariable String productId) {
        service.delete(productId);
        return ApiResponse.ok(null);
    }
}
