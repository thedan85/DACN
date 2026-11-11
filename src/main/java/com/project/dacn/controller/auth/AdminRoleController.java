package com.project.dacn.controller.auth;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.request.auth.AdminRoleCreateRequest;
import com.project.dacn.dto.request.auth.AdminRolePermissionsUpdateRequest;
import com.project.dacn.dto.request.auth.AdminRoleUpdateRequest;
import com.project.dacn.dto.response.auth.AdminRoleDetailResponse;
import com.project.dacn.dto.response.auth.AdminRoleSummaryResponse;
import com.project.dacn.dto.response.auth.PermissionResponse;
import com.project.dacn.security.Permissions;
import com.project.dacn.service.auth.AdminRoleService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "admin-roles")
@SecurityRequirement(name = "bearerAuth")
public class AdminRoleController {

    private final AdminRoleService service;

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    public ApiResponse<List<PermissionResponse>> listPermissions() {
        return ApiResponse.ok(service.listPermissions());
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    public ApiResponse<List<AdminRoleSummaryResponse>> listRoles() {
        return ApiResponse.ok(service.listRoles());
    }

    @GetMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    public ApiResponse<AdminRoleDetailResponse> getRole(@PathVariable String roleId) {
        return ApiResponse.ok(service.getRole(roleId));
    }

    @PatchMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_UPDATE + "')")
    public ApiResponse<AdminRoleDetailResponse> updateRole(
            @PathVariable String roleId,
            @Valid @RequestBody AdminRoleUpdateRequest request) {
        return ApiResponse.ok(service.updateRole(roleId, request));
    }

    @PutMapping("/roles/{roleId}/permissions")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_UPDATE + "')")
    public ApiResponse<AdminRoleDetailResponse> replacePermissions(
            @PathVariable String roleId,
            @Valid @RequestBody AdminRolePermissionsUpdateRequest request) {
        return ApiResponse.ok(service.replacePermissions(roleId, request));
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_CREATE + "')")
    public ResponseEntity<ApiResponse<AdminRoleDetailResponse>> createRole(
            @Valid @RequestBody AdminRoleCreateRequest request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.of(201, "Created", service.createRole(request)));
    }

    @DeleteMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_DELETE + "')")
    public ApiResponse<Void> deleteRole(@PathVariable String roleId) {
        service.deleteRole(roleId);
        return ApiResponse.ok(null);
    }
}
