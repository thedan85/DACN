package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductAttributeValueCreateRequest;
import com.project.dacn.dto.response.catalog.ProductAttributeValueResponse;
import com.project.dacn.service.catalog.ProductAttributeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProductAttributeController {

    private final ProductAttributeService service;

    @GetMapping("/api/v1/products/{productId}/attributes")
    public ApiResponse<PageResponse<ProductAttributeValueResponse>> listByProduct(
            @PathVariable String productId, @ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.listByProduct(productId, pageable));
    }

    @GetMapping("/api/v1/product-attributes/{productValueId}")
    public ApiResponse<ProductAttributeValueResponse> get(@PathVariable String productValueId) {
        return ApiResponse.ok(service.get(productValueId));
    }

    @PostMapping("/api/v1/product-attributes")
    public ApiResponse<ProductAttributeValueResponse> create(
            @Valid @RequestBody ProductAttributeValueCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @DeleteMapping("/api/v1/product-attributes/{productValueId}")
    public ApiResponse<Void> delete(@PathVariable String productValueId) {
        service.delete(productValueId);
        return ApiResponse.ok(null);
    }
}
