package com.project.dacn.controller.auth;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.auth.AdminUserCreateRequest;
import com.project.dacn.dto.request.auth.AdminUserRoleRequest;
import com.project.dacn.dto.request.auth.AdminUserStatusRequest;
import com.project.dacn.dto.response.auth.AdminUserDetailResponse;
import com.project.dacn.dto.response.auth.AdminUserResponse;
import com.project.dacn.security.Permissions;
import com.project.dacn.service.auth.AdminUserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Tag(name = "admin-users")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final AdminUserService service;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    public ApiResponse<PageResponse<AdminUserResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) UserStatus status,
            @ParameterObject Pageable pageable) {
        return ApiResponse.ok(service.list(keyword, roleCode, status, pageable));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    public ApiResponse<AdminUserDetailResponse> get(@PathVariable String userId) {
        return ApiResponse.ok(service.get(userId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.USER_CREATE + "')")
    public ResponseEntity<ApiResponse<AdminUserDetailResponse>> create(
            @Valid @RequestBody AdminUserCreateRequest request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.of(201, "Created", service.create(request)));
    }

    @PatchMapping("/{userId}/status")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    public ApiResponse<AdminUserDetailResponse> updateStatus(
            @PathVariable String userId,
            @Valid @RequestBody AdminUserStatusRequest request) {
        return ApiResponse.ok(service.updateStatus(userId, request));
    }

    @PatchMapping("/{userId}/role")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    public ApiResponse<AdminUserDetailResponse> updateRole(
            @PathVariable String userId,
            @Valid @RequestBody AdminUserRoleRequest request) {
        return ApiResponse.ok(service.updateRole(userId, request));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_DELETE + "')")
    public ApiResponse<Void> delete(@PathVariable String userId) {
        service.delete(userId);
        return ApiResponse.ok(null);
    }
}
