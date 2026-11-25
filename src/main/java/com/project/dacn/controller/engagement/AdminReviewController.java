package com.project.dacn.controller.engagement;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.response.engagement.AdminReviewResponse;
import com.project.dacn.security.Permissions;
import com.project.dacn.service.engagement.ReviewService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/admin/reviews")
@RequiredArgsConstructor
@Tag(name = "admin-reviews")
@SecurityRequirement(name = "bearerAuth")
public class AdminReviewController {

    private final ReviewService service;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.REVIEW_READ + "')")
    public ApiResponse<PageResponse<AdminReviewResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer rating,
            @ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.listForAdmin(keyword, rating, pageable));
    }

    @DeleteMapping("/{reviewId}")
    @PreAuthorize("hasAuthority('" + Permissions.REVIEW_DELETE + "')")
    public ApiResponse<Void> delete(Principal principal, @PathVariable String reviewId) {
        service.deleteAsAdmin(reviewId, principal.getName());
        return ApiResponse.ok(null);
    }
}
