package com.project.dacn.controller.catalog;

import com.project.dacn.common.constant.ProductSort;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.request.catalog.ProductFilter;
import com.project.dacn.dto.request.catalog.ProductUpdateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.security.Permissions;
import com.project.dacn.service.catalog.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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

import java.math.BigDecimal;



@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService service;

    @GetMapping
    public ApiResponse<PageResponse<ProductResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "category_id", required = false) String categoryId,
            @RequestParam(name = "min_price", required = false) BigDecimal minPrice,
            @RequestParam(name = "max_price", required = false) BigDecimal maxPrice,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false, defaultValue = "newest") ProductSort sort,
            @ParameterObject Pageable pageable,
            Authentication auth) {
        ProductFilter filter = new ProductFilter(
                keyword, visibleStatus(status, auth), categoryId, minPrice, maxPrice, sort);
        return ApiResponse.ok(service.search(filter, pageable));
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<ProductResponse>> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String categoryId,
            @ParameterObject Pageable pageable,
            Authentication auth) {
        return ApiResponse.ok(service.search(
                new ProductFilter(name, visibleStatus(status, auth), categoryId), pageable));
    }

    @GetMapping("/{slug}")
    public ApiResponse<ProductResponse> get(@PathVariable String slug, Authentication auth) {
        return ApiResponse.ok(service.getBySlug(slug, canViewHidden(auth)));
    }

    /**
     * Only catalog managers may ask for DRAFT/INACTIVE (or DELETED for audit);
     * everyone else is pinned to ACTIVE regardless of the query param.
     */
    private static ProductStatus visibleStatus(ProductStatus requested, Authentication auth) {
        if (!canViewHidden(auth)) {
            return ProductStatus.ACTIVE;
        }
        return requested == null ? ProductStatus.ACTIVE : requested;
    }

    private static boolean canViewHidden(Authentication auth) {
        return Permissions.has(auth, Permissions.VIEW_HIDDEN_PRODUCTS);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:create')")
    public ApiResponse<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasAuthority('product:update')")
    public ApiResponse<ProductResponse> replace(@PathVariable String productId,
            @Valid @RequestBody ProductUpdateRequest request) {
        return ApiResponse.ok(service.replace(productId, request));
    }

    @PatchMapping("/{productId}")
    @PreAuthorize("hasAuthority('product:update')")
    public ApiResponse<ProductResponse> patch(@PathVariable String productId,
            @Valid @RequestBody ProductUpdateRequest request) {
        return ApiResponse.ok(service.update(productId, request));
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasAuthority('product:delete')")
    public ApiResponse<Void> delete(@PathVariable String productId) {
        service.delete(productId);
        return ApiResponse.ok(null);
    }
}
