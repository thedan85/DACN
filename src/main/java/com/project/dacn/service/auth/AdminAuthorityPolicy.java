package com.project.dacn.service.auth;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.common.exception.AdminAuthorityException;
import com.project.dacn.entity.auth.Permission;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single authorization policy for admin user and role mutations.
 *
 * <p>URL-level {@code user:*} / {@code role:*} checks only prove that the actor
 * may call an endpoint. They do not prove that the actor may hand out the
 * privileges that endpoint manipulates. This policy closes that gap:
 *
 * <ul>
 *   <li>only an active {@code admin} may create an admin, move a user to or
 *       from the admin role, modify the admin role, change a
 *       {@code user:*} / {@code role:*} grant, or lock, delete or demote an
 *       admin account;</li>
 *   <li>nobody may change their own role;</li>
 *   <li>assigning a role or granting a permission requires the actor to hold
 *       every permission being handed out. The {@code admin} role holds every
 *       permission in production, so an admin passes this check by design.</li>
 * </ul>
 *
 * <p>Every violation raises {@link AdminAuthorityException}, which the global
 * handler renders as HTTP 403 with the same message.
 */
@Component
@RequiredArgsConstructor
public class AdminAuthorityPolicy {

    static final String ADMIN_ROLE_CODE = "admin";

    /** Resources whose grants are privileged enough to be admin-only. */
    private static final Set<String> PRIVILEGED_RESOURCES = Set.of("user", "role");

    private final UserRepository userRepository;
    private final RolePermissionRepository rolePermissionRepository;

    /**
     * Resolves the authenticated actor from the database so role and status are
     * current. The JWT filter already rejects locked/deleted principals; this
     * check keeps the policy safe if it is ever called from another entry
     * point.
     */
    public Actor requireActiveActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AdminAuthorityException();
        }

        User actor = userRepository.findByUserId(authentication.getName())
                .orElseThrow(AdminAuthorityException::new);
        if (actor.getStatus() != UserStatus.ACTIVE) {
            throw new AdminAuthorityException();
        }

        Set<String> permissions = rolePermissionRepository
                .findByRole_RoleIdWithPermission(actor.getRole().getRoleId()).stream()
                .map(RolePermission::getPermission)
                .map(Permission::getCode)
                .collect(Collectors.toUnmodifiableSet());

        return new Actor(actor.getUserId(), normalize(actor.getRole().getCode()), permissions);
    }

    public void requireAdmin(Actor actor) {
        if (!actor.admin()) {
            throw new AdminAuthorityException();
        }
    }

    /**
     * A role may be assigned on create or on a role change only when the actor
     * is an admin or already holds every permission the role grants.
     */
    public void requireRoleAssignable(Actor actor, Role role) {
        if (isAdminRole(role)) {
            requireAdmin(actor);
            return;
        }
        if (actor.admin()) {
            return;
        }
        ensureActorHolds(actor, permissionCodesOf(role));
    }

    /** Renaming/describing the admin role, or replacing its grants, is admin-only. */
    public void requireRoleModifiable(Actor actor, Role role) {
        if (isAdminRole(role)) {
            requireAdmin(actor);
        }
    }

    /**
     * A role change to or from {@code admin} is admin-only, and no actor may
     * change their own role. Existing 409 guards (self-demote, last active
     * admin) are evaluated by the service before this policy, so their status
     * and message are preserved.
     */
    public void requireRoleChange(Actor actor, User target, String requestedRoleCode) {
        if (isAdminCode(roleCodeOf(target)) || isAdminCode(requestedRoleCode)) {
            requireAdmin(actor);
        }
        if (actor.userId().equals(target.getUserId())) {
            throw new AdminAuthorityException();
        }
    }

    /**
     * Applies the permission-level rules to a whole-set replacement:
     *
     * <ul>
     *   <li>modifying the admin role is admin-only;</li>
     *   <li>granting or revoking a {@code user:*} / {@code role:*} permission
     *       is admin-only;</li>
     *   <li>a non-admin may only add permissions it already holds.</li>
     * </ul>
     *
     * Revoking an ordinary (non user/role) permission does not require holding
     * it: dropping authority never escalates the actor.
     */
    public void requirePermissionChange(
            Actor actor,
            Role role,
            Collection<Permission> added,
            Collection<Permission> removed) {
        requireRoleModifiable(actor, role);
        if (actor.admin()) {
            return;
        }

        boolean touchesPrivilegedGrant = added.stream().anyMatch(AdminAuthorityPolicy::isPrivileged)
                || removed.stream().anyMatch(AdminAuthorityPolicy::isPrivileged);
        if (touchesPrivilegedGrant) {
            throw new AdminAuthorityException();
        }

        ensureActorHolds(actor, added.stream().map(Permission::getCode).collect(Collectors.toSet()));
    }

    public boolean isAdmin(Actor actor) {
        return actor.admin();
    }

    public boolean isAdminRole(Role role) {
        return role != null && isAdminCode(role.getCode());
    }

    private Set<String> permissionCodesOf(Role role) {
        return rolePermissionRepository.findByRole_RoleIdWithPermission(role.getRoleId()).stream()
                .map(RolePermission::getPermission)
                .map(Permission::getCode)
                .collect(Collectors.toSet());
    }

    private void ensureActorHolds(Actor actor, Collection<String> permissionCodes) {
        boolean missing = permissionCodes.stream().anyMatch(code -> !actor.permissions().contains(code));
        if (missing) {
            throw new AdminAuthorityException();
        }
    }

    private static boolean isPrivileged(Permission permission) {
        return permission.getResource() != null
                && PRIVILEGED_RESOURCES.contains(normalize(permission.getResource()));
    }

    private static boolean isAdminCode(String roleCode) {
        return ADMIN_ROLE_CODE.equals(normalize(roleCode));
    }

    private static String roleCodeOf(User user) {
        return user.getRole() == null ? "" : normalize(user.getRole().getCode());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Immutable snapshot of the authenticated actor used for one request. */
    public record Actor(String userId, String roleCode, Set<String> permissions) {

        public boolean admin() {
            return ADMIN_ROLE_CODE.equals(roleCode);
        }
    }
}
