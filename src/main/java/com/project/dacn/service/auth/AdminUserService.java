package com.project.dacn.service.auth;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.exception.UserRuleViolationException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.auth.AdminUserCreateRequest;
import com.project.dacn.dto.request.auth.AdminUserRoleRequest;
import com.project.dacn.dto.request.auth.AdminUserStatusRequest;
import com.project.dacn.dto.response.auth.AdminUserDetailResponse;
import com.project.dacn.dto.response.auth.AdminUserResponse;
import com.project.dacn.entity.auth.RefreshToken;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.identity.Admin;
import com.project.dacn.entity.identity.Employee;
import com.project.dacn.repository.auth.AdminUserQueryRepository;
import com.project.dacn.repository.auth.RefreshTokenRepository;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.identity.AdminRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.identity.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private static final Set<String> SORT_FIELDS = Set.of(
            "email", "name", "roleCode", "status", "lastLogin", "createdAt");

    private final AdminUserQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final EmployeeRepository employeeRepository;
    private final AdminRepository adminRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(
            String keyword, String roleCode, UserStatus status, Pageable pageable) {
        if (status == UserStatus.DELETED) {
            throw new IllegalArgumentException("DELETED is not a valid status filter");
        }
        validateSort(pageable);

        String keywordFilter = normalize(keyword);
        String roleFilter = normalize(roleCode);
        String statusFilter = status == null ? "" : status.name();

        Page<AdminUserQueryRepository.Row> page =
                queryRepository.search(keywordFilter, roleFilter, statusFilter, pageable);

        List<AdminUserResponse> content = page.getContent().stream()
                .map(row -> new AdminUserResponse(
                        row.userId(),
                        row.email(),
                        row.name(),
                        row.phone(),
                        row.roleCode(),
                        row.roleName(),
                        row.status(),
                        row.lastLogin(),
                        row.createdAt()))
                .toList();

        return PageResponse.from(page, content);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse get(String userId) {
        User user = requireUser(userId);

        String roleCode = roleCodeOf(user);
        Profile profile = profileFor(user, roleCode);

        List<String> permissions = rolePermissionRepository
                .findByRole_RoleIdWithPermission(user.getRole().getRoleId()).stream()
                .map(RolePermission::getPermission)
                .map(permission -> permission.getCode())
                .sorted()
                .toList();

        return new AdminUserDetailResponse(
                user.getUserId(),
                user.getEmail(),
                profile.name(),
                profile.phone(),
                profile.address(),
                roleCode,
                user.getRole().getName(),
                user.getStatus(),
                user.getLastLogin(),
                user.getCreatedAt(),
                permissions);
    }

    @Transactional
    public AdminUserDetailResponse create(AdminUserCreateRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.findByEmailIncludingDeleted(email).isPresent()) {
            throw new UserRuleViolationException("error.email.exists");
        }

        String roleCode = normalize(request.roleCode());
        if (roleCode.equals("customer")) {
            throw new UserRuleViolationException("error.user.role.invalid");
        }

        Role role = roleRepository.findByCodeIgnoreCase(roleCode)
                .orElseThrow(() -> new UserRuleViolationException("error.user.role.invalid"));

        String phone = trimToNull(request.phone());
        if (phone == null) {
            throw new UserRuleViolationException("error.user.profile.phone");
        }
        String name = request.name().trim();
        String address = trimToNull(request.address());

        User created = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(role)
                .status(UserStatus.ACTIVE)
                .build();

        try {
            created = userRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException ex) {
            throw new UserRuleViolationException("error.email.exists");
        }

        if (roleCode.equals("admin")) {
            adminRepository.save(Admin.builder()
                    .user(created)
                    .name(name)
                    .phone(phone)
                    .address(address)
                    .build());
        } else {
            employeeRepository.save(Employee.builder()
                    .user(created)
                    .name(name)
                    .phone(phone)
                    .address(address)
                    .build());
        }

        log.info("admin user action: actor={}, target={}, action=CREATE",
                currentActorId(), created.getUserId());
        return get(created.getUserId());
    }

    @Transactional
    public AdminUserDetailResponse updateStatus(String userId, AdminUserStatusRequest request) {
        User target = requireUser(userId);
        UserStatus targetStatus = parseStatus(request.status());
        String actor = currentActorId();

        if (targetStatus == UserStatus.LOCKED && Objects.equals(actor, userId)) {
            throw new UserRuleViolationException("error.user.self.lock");
        }
        if (targetStatus == UserStatus.LOCKED) {
            ensureNotLastActiveAdmin(target);
        }

        target.setStatus(targetStatus);
        userRepository.save(target);
        if (targetStatus == UserStatus.LOCKED) {
            revokeAllRefreshTokens(userId);
        }

        log.info("admin user action: actor={}, target={}, action=STATUS_{}",
                actor, userId, targetStatus);
        return get(userId);
    }

    @Transactional
    public AdminUserDetailResponse updateRole(String userId, AdminUserRoleRequest request) {
        User target = requireUser(userId);
        String currentRole = roleCodeOf(target);
        String requestedRole = normalize(request.roleCode());

        if (requestedRole.equals("customer")) {
            throw new UserRuleViolationException("error.user.role.invalid");
        }
        if (currentRole.equals("customer")) {
            throw new UserRuleViolationException("error.user.customer.role");
        }
        if (currentRole.equals(requestedRole)) {
            return get(userId);
        }

        boolean demote = currentRole.equals("admin") && !requestedRole.equals("admin");
        String actor = currentActorId();
        if (demote && Objects.equals(actor, userId)) {
            throw new UserRuleViolationException("error.user.self.demote");
        }
        if (demote) {
            ensureNotLastActiveAdmin(target);
        }

        Role targetRole = roleRepository.findByCodeIgnoreCase(requestedRole)
                .orElseThrow(() -> new UserRuleViolationException("error.user.role.invalid"));

        Profile currentProfile = profileFor(target, currentRole);
        if (requestedRole.equals("admin")) {
            if (adminRepository.findByUser_UserId(userId).isEmpty()) {
                adminRepository.save(Admin.builder()
                        .user(target)
                        .name(displayName(currentProfile, target))
                        .phone(currentProfile.phone())
                        .address(currentProfile.address())
                        .build());
            }
        } else {
            if (employeeRepository.findByUser_UserId(userId).isEmpty()) {
                employeeRepository.save(Employee.builder()
                        .user(target)
                        .name(displayName(currentProfile, target))
                        .phone(currentProfile.phone())
                        .address(currentProfile.address())
                        .build());
            }
        }

        target.setRole(targetRole);
        userRepository.save(target);

        log.info("admin user action: actor={}, target={}, action=ROLE_{}",
                actor, userId, requestedRole.toUpperCase(Locale.ROOT));
        return get(userId);
    }

    @Transactional
    public void delete(String userId) {
        User target = requireUser(userId);
        String actor = currentActorId();

        if (Objects.equals(actor, userId)) {
            throw new UserRuleViolationException("error.user.self.delete");
        }
        ensureNotLastActiveAdmin(target);

        target.setStatus(UserStatus.DELETED);
        userRepository.save(target);
        revokeAllRefreshTokens(userId);

        log.info("admin user action: actor={}, target={}, action=DELETE", actor, userId);
    }

    private User requireUser(String userId) {
        return userRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user not found: " + userId));
    }

    private void ensureNotLastActiveAdmin(User target) {
        if (target.getStatus() != UserStatus.ACTIVE || !roleCodeOf(target).equals("admin")) {
            return;
        }

        List<User> activeAdmins = userRepository.findByRoleCodeAndStatusForUpdate(
                "admin", UserStatus.ACTIVE);
        boolean targetIsActiveAdmin = activeAdmins.stream()
                .anyMatch(admin -> admin.getId().equals(target.getId()));
        if (targetIsActiveAdmin && activeAdmins.size() <= 1) {
            throw new UserRuleViolationException("error.user.last.admin");
        }
    }

    private void revokeAllRefreshTokens(String userId) {
        List<RefreshToken> tokens = refreshTokenRepository.findByUser_UserId(userId);
        tokens.forEach(token -> token.setRevoked(true));
        refreshTokenRepository.saveAll(tokens);
    }

    private UserStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new UserRuleViolationException("error.user.status.invalid");
        }
        try {
            UserStatus parsed = UserStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (parsed == UserStatus.DELETED) {
                throw new UserRuleViolationException("error.user.status.invalid");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new UserRuleViolationException("error.user.status.invalid");
        }
    }

    private Profile profileFor(User user, String roleCode) {
        return switch (roleCode) {
            case "customer" -> customerRepository.findByUser_UserId(user.getUserId())
                    .map(customer -> new Profile(
                            customer.getName(), customer.getPhone(), customer.getAddress()))
                    .orElse(Profile.EMPTY);
            case "admin" -> adminRepository.findByUser_UserId(user.getUserId())
                    .map(admin -> new Profile(
                            admin.getName(), admin.getPhone(), admin.getAddress()))
                    .orElse(Profile.EMPTY);
            default -> employeeRepository.findByUser_UserId(user.getUserId())
                    .map(employee -> new Profile(
                            employee.getName(), employee.getPhone(), employee.getAddress()))
                    .orElse(Profile.EMPTY);
        };
    }

    private static String displayName(Profile profile, User user) {
        return profile.name() == null || profile.name().isBlank()
                ? user.getEmail()
                : profile.name();
    }

    private static String currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    private static String roleCodeOf(User user) {
        return normalize(user.getRole().getCode());
    }

    private static void validateSort(Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!SORT_FIELDS.contains(order.getProperty())) {
                throw new IllegalArgumentException(
                        "unsupported sort field: " + order.getProperty());
            }
        });
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Profile(String name, String phone, String address) {
        private static final Profile EMPTY = new Profile(null, null, null);
    }
}
