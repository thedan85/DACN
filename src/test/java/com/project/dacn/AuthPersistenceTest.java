package com.project.dacn;

import com.project.dacn.entity.Permission;
import com.project.dacn.entity.RefreshToken;
import com.project.dacn.entity.Role;
import com.project.dacn.entity.RolePermission;
import com.project.dacn.entity.User;
import com.project.dacn.repository.PermissionRepository;
import com.project.dacn.repository.RefreshTokenRepository;
import com.project.dacn.repository.RolePermissionRepository;
import com.project.dacn.repository.RoleRepository;
import com.project.dacn.repository.UserRepository;
import com.project.dacn.common.constant.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class AuthPersistenceTest {

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    PermissionRepository permissionRepository;

    @Autowired
    RolePermissionRepository rolePermissionRepository;

    @Test
    void persistRoleAndUserThroughBusinessKeyFk() {
        Role role = Role.builder()
                .code("customer")
                .name("Customer")
                .system(true)
                .build();
        role = roleRepository.save(role);
        assertThat(role.getId()).isNotNull();
        assertThat(role.getRoleId()).isNotNull();

        User user = User.builder()
                .email("a@example.com")
                .passwordHash("hash")
                .status(UserStatus.ACTIVE)
                .role(role)
                .build();
        user = userRepository.save(user);
        assertThat(user.getId()).isNotNull();
        assertThat(user.getUserId()).isNotNull();

        User found = userRepository.findByUserId(user.getUserId()).orElseThrow();
        assertThat(found.getRole().getRoleId()).isEqualTo(role.getRoleId());
    }

    @Test
    void duplicateEmailRejected() {
        Role role = roleRepository.save(Role.builder().code("c1").name("C1").build());
        userRepository.save(User.builder().email("dup@example.com").passwordHash("h").role(role).build());

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                User.builder().email("dup@example.com").passwordHash("h2").role(role).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistRefreshTokenPermissionAndGrant() {
        Role role = roleRepository.save(Role.builder().code("admin").name("Admin").build());
        User user = userRepository.save(User.builder().email("t@example.com")
                .passwordHash("h").role(role).build());
        RefreshToken token = refreshTokenRepository.save(RefreshToken.builder()
                .user(user).tokenHash("thash")
                .expiresAt(java.time.Instant.now().plusSeconds(3600)).build());
        assertThat(token.getTokenId()).isNotNull();

        Permission perm = permissionRepository.save(Permission.builder()
                .code("product:create").name("Create product")
                .resource("product").action("create").build());
        assertThat(perm.getPermissionId()).isNotNull();

        RolePermission grant = rolePermissionRepository.save(
                RolePermission.builder().role(role).permission(perm).build());
        assertThat(grant.getRolePermissionId()).isNotNull();

        assertThatThrownBy(() -> rolePermissionRepository.saveAndFlush(
                RolePermission.builder().role(role).permission(perm).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void softDeletedUserHiddenByDefault() {
        Role role = roleRepository.save(Role.builder().code("c2").name("C2").build());
        User user = userRepository.save(User.builder().email("del@example.com")
                .passwordHash("h").role(role).build());
        user.setStatus(UserStatus.DELETED);
        userRepository.save(user);

        assertThat(userRepository.findByUserId(user.getUserId())).isEmpty();
        assertThat(userRepository.findByStatusNot(UserStatus.DELETED)).isEmpty();
    }
}
