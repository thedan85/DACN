package com.project.dacn.controller.engagement;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.engagement.ReviewCreateRequest;
import com.project.dacn.dto.request.engagement.ReviewUpdateRequest;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.security.CustomerIdentity;
import com.project.dacn.security.Permissions;
import com.project.dacn.service.engagement.ReviewService;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "reviews")
public class ReviewController {

    private final ReviewService service;
    private final CustomerIdentity identity;

    @PostMapping("/api/v1/variants/{variantId}/reviews")
    public ApiResponse<ReviewResponse> create(
            Authentication auth,
            @PathVariable String variantId,
            @Valid @RequestBody ReviewCreateRequest request) {
        return ApiResponse.ok(service.create(
                identity.requireCustomerId(auth), variantId, request));
    }

    @PatchMapping("/api/v1/reviews/{reviewId}")
    public ApiResponse<ReviewResponse> update(
            Authentication auth,
            @PathVariable String reviewId,
            @Valid @RequestBody ReviewUpdateRequest request) {
        return ApiResponse.ok(service.update(
                identity.requireCustomerId(auth), reviewId, request));
    }

    @DeleteMapping("/api/v1/reviews/{reviewId}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable String reviewId) {
        service.delete(identity.requireCustomerId(auth), reviewId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/v1/orders/{orderId}/reviews")
    public ApiResponse<PageResponse<ReviewResponse>> listForOrder(
            Authentication auth,
            @PathVariable String orderId,
            @ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.listForOrder(
                identity.requireCustomerId(auth), orderId, pageable));
    }

    @GetMapping("/api/v1/staff/orders/{orderId}/reviews")
    @PreAuthorize("hasAuthority('" + Permissions.ORDER_READ + "')")
    public ApiResponse<PageResponse<ReviewResponse>> listForStaffOrder(
            @PathVariable String orderId,
            @ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.listForOrderAsStaff(orderId, pageable));
    }

    @GetMapping("/api/v1/variants/{variantId}/reviews")
    public ApiResponse<PageResponse<ReviewResponse>> listForVariant(
            Authentication auth,
            @PathVariable String variantId,
            @ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.listForVariant(
                variantId, canViewHidden(auth), pageable));
    }

    private static boolean canViewHidden(Authentication auth) {
        return Permissions.has(auth, Permissions.VIEW_HIDDEN_PRODUCTS);
    }
}
