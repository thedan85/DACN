package com.project.dacn.service.auth;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.exception.RoleRuleViolationException;
import com.project.dacn.dto.request.auth.AdminRoleCreateRequest;
import com.project.dacn.dto.request.auth.AdminRolePermissionsUpdateRequest;
import com.project.dacn.dto.request.auth.AdminRoleUpdateRequest;
import com.project.dacn.dto.response.auth.AdminRoleDetailResponse;
import com.project.dacn.dto.response.auth.AdminRoleSummaryResponse;
import com.project.dacn.dto.response.auth.PermissionResponse;
import com.project.dacn.entity.auth.Permission;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.repository.auth.PermissionRepository;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminRoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions() {
        return permissionRepository.findAllByOrderByResourceAscActionAscCodeAsc().stream()
                .map(this::toPermissionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminRoleSummaryResponse> listRoles() {
        return roleRepository.findByDeletedFalseOrderBySystemDescCodeAsc().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminRoleDetailResponse getRole(String roleId) {
        Role role = roleRepository.findByRoleId(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("role not found: " + roleId));

        List<PermissionResponse> permissions = rolePermissionRepository
                .findByRole_RoleIdWithPermission(roleId).stream()
                .map(RolePermission::getPermission)
                .sorted(Comparator.comparing(Permission::getCode))
                .map(this::toPermissionResponse)
                .toList();

        return new AdminRoleDetailResponse(
                role.getRoleId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                Boolean.TRUE.equals(role.getSystem()),
                role.getCreatedAt(),
                role.getUpdatedAt(),
                permissions);
    }

    @Transactional
    public AdminRoleDetailResponse updateRole(String roleId, AdminRoleUpdateRequest request) {
        Role role = roleRepository.findByRoleId(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("role not found: " + roleId));

        if (request.name() == null && request.description() == null) {
            throw new IllegalArgumentException("at least one of name or description is required");
        }

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            role.setName(name);
        }

        if (request.description() != null) {
            String description = request.description().trim();
            role.setDescription(description.isEmpty() ? null : description);
        }

        roleRepository.save(role);
        return getRole(roleId);
    }

    @Transactional
    public AdminRoleDetailResponse replacePermissions(
            String roleId, AdminRolePermissionsUpdateRequest request) {
        Role role = roleRepository.findByRoleIdForUpdate(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("role not found: " + roleId));

        List<String> requestedIds = request.permissionIds();
        if (requestedIds == null
                || requestedIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("permission ids must not be blank");
        }

        Set<String> targetIds = new HashSet<>(requestedIds);
        if (targetIds.size() != requestedIds.size()) {
            throw new IllegalArgumentException("duplicate permission ids");
        }

        List<Permission> target = permissionRepository.findByPermissionIdIn(requestedIds);
        if (target.size() != requestedIds.size()) {
            Set<String> found = target.stream()
                    .map(Permission::getPermissionId)
                    .collect(Collectors.toSet());
            String missing = requestedIds.stream()
                    .filter(id -> !found.contains(id))
                    .findFirst()
                    .orElse("unknown");
            throw new ResourceNotFoundException("permission not found: " + missing);
        }

        ensureAdminKeepsUserAndRolePermissions(role, targetIds);

        List<RolePermission> current = rolePermissionRepository
                .findByRole_RoleIdWithPermission(roleId);
        Set<String> currentIds = current.stream()
                .map(grant -> grant.getPermission().getPermissionId())
                .collect(Collectors.toSet());

        List<RolePermission> toDelete = current.stream()
                .filter(grant -> !targetIds.contains(grant.getPermission().getPermissionId()))
                .toList();
        if (!toDelete.isEmpty()) {
            rolePermissionRepository.deleteAll(toDelete);
        }

        List<RolePermission> toAdd = target.stream()
                .filter(permission -> !currentIds.contains(permission.getPermissionId()))
                .map(permission -> RolePermission.builder()
                        .role(role)
                        .permission(permission)
                        .build())
                .toList();
        if (!toAdd.isEmpty()) {
            rolePermissionRepository.saveAll(toAdd);
        }
        rolePermissionRepository.flush();

        return getRole(roleId);
    }

    @Transactional
    public AdminRoleDetailResponse createRole(AdminRoleCreateRequest request) {
        String code = request.code().trim().toLowerCase(Locale.ROOT);

        if (roleRepository.countByCodeIgnoreCaseIncludingDeleted(code) > 0) {
            throw new RoleRuleViolationException("error.role.code.exists");
        }

        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }

        String description = request.description() == null
                ? null
                : request.description().trim();
        if (description != null && description.isEmpty()) {
            description = null;
        }

        Role role = Role.builder()
                .code(code)
                .name(name)
                .description(description)
                .system(false)
                .deleted(false)
                .build();

        try {
            role = roleRepository.saveAndFlush(role);
        } catch (DataIntegrityViolationException ex) {
            throw new RoleRuleViolationException("error.role.code.exists");
        }

        return getRole(role.getRoleId());
    }

    @Transactional
    public void deleteRole(String roleId) {
        Role role = roleRepository.findByRoleIdForUpdate(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("role not found: " + roleId));

        if (Boolean.TRUE.equals(role.getSystem())) {
            throw new RoleRuleViolationException("error.role.system.delete");
        }

        if (userRepository.countByRoleIdIncludingDeleted(role.getId()) > 0) {
            throw new RoleRuleViolationException("error.role.in.use");
        }

        role.setDeleted(true);
        roleRepository.saveAndFlush(role);
    }

    private void ensureAdminKeepsUserAndRolePermissions(Role role, Set<String> targetIds) {
        if (!Boolean.TRUE.equals(role.getSystem())
                || !"admin".equalsIgnoreCase(role.getCode())) {
            return;
        }

        boolean missing = permissionRepository.findAll().stream()
                .filter(permission -> "user".equalsIgnoreCase(permission.getResource())
                        || "role".equalsIgnoreCase(permission.getResource()))
                .anyMatch(permission -> !targetIds.contains(permission.getPermissionId()));

        if (missing) {
            throw new RoleRuleViolationException("error.role.admin.permissions");
        }
    }

    private AdminRoleSummaryResponse toSummary(Role role) {
        return new AdminRoleSummaryResponse(
                role.getRoleId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                Boolean.TRUE.equals(role.getSystem()),
                role.getCreatedAt(),
                role.getUpdatedAt());
    }

    private PermissionResponse toPermissionResponse(Permission permission) {
        return new PermissionResponse(
                permission.getPermissionId(),
                permission.getCode(),
                permission.getName(),
                permission.getResource(),
                permission.getAction(),
                permission.getDescription(),
                permission.getCreatedAt(),
                permission.getUpdatedAt());
    }
}
