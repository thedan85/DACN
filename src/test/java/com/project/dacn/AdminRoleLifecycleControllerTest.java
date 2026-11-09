package com.project.dacn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.dto.request.auth.LoginRequest;
import com.project.dacn.entity.auth.Permission;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.PermissionRepository;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.RateLimiter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminRoleLifecycleControllerTest {

    private static final String PASSWORD = "password123";

    @MockitoBean
    private RateLimiter rateLimiter;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private Role adminRole;
    private Role assignedRole;
    private Role deletedUserRole;
    private Role unusedRole;

    @BeforeEach
    void seedRolesAndUsers() {
        adminRole = role("ADMIN", "Administrator", true, false);
        Role employeeRole = role("EMPLOYEE", "Employee", true, false);
        Role customerRole = role("CUSTOMER", "Customer", true, false);
        assignedRole = role("SUPPORT", "Support", false, false);
        deletedUserRole = role("ARCHIVE", "Archive", false, false);
        unusedRole = role("AUDITOR", "Auditor", false, false);
        role("LEGACY", "Legacy", false, true);

        Permission roleRead = permission("role:read", "Read Role", "role", "read");
        Permission roleCreate = permission("role:create", "Create Role", "role", "create");
        Permission roleUpdate = permission("role:update", "Update Role", "role", "update");
        Permission roleDelete = permission("role:delete", "Delete Role", "role", "delete");
        Permission userRead = permission("user:read", "Read User", "user", "read");
        Permission productCreate = permission("product:create", "Create Product", "product", "create");

        grant(adminRole, roleRead);
        grant(adminRole, roleCreate);
        grant(adminRole, roleUpdate);
        grant(adminRole, roleDelete);
        grant(adminRole, userRead);
        grant(unusedRole, productCreate);

        user("admin@lifecycle.test", adminRole, UserStatus.ACTIVE);
        user("employee@lifecycle.test", employeeRole, UserStatus.ACTIVE);
        user("customer@lifecycle.test", customerRole, UserStatus.ACTIVE);
        user("staff@lifecycle.test", assignedRole, UserStatus.ACTIVE);
        user("gone@lifecycle.test", deletedUserRole, UserStatus.DELETED);

        entityManager.flush();
    }

    @Test
    void createAndDeleteEndpoints_withoutToken_return401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "NEW_ROLE",
                                "name", "New Role"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(delete("/api/v1/admin/roles/" + unusedRole.getRoleId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void customerAndPermissionlessEmployee_forbiddenOnCreateAndDelete() throws Exception {
        String customerToken = login("customer@lifecycle.test");
        String employeeToken = login("employee@lifecycle.test");

        for (String token : new String[]{customerToken, employeeToken}) {
            mockMvc.perform(post("/api/v1/admin/roles")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "code", "NEW_ROLE",
                                    "name", "New Role"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));

            mockMvc.perform(delete("/api/v1/admin/roles/" + unusedRole.getRoleId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    @Test
    void createRole_returns201WithLowercaseCodeAndNoPermissions() throws Exception {
        String token = login("admin@lifecycle.test");

        mockMvc.perform(post("/api/v1/admin/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "SUPPORT_DESK",
                                "name", "Support Desk",
                                "description", "Handles tickets"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.roleId").value("ROLE_support-desk"))
                .andExpect(jsonPath("$.data.code").value("support_desk"))
                .andExpect(jsonPath("$.data.name").value("Support Desk"))
                .andExpect(jsonPath("$.data.description").value("Handles tickets"))
                .andExpect(jsonPath("$.data.system").value(false))
                .andExpect(jsonPath("$.data.permissions.length()").value(0));
    }

    @Test
    void createRole_duplicateCodeDifferentCase_409() throws Exception {
        String token = login("admin@lifecycle.test");

        mockMvc.perform(post("/api/v1/admin/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "support",
                                "name", "Another Support"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void createRole_duplicateCodeOfSoftDeletedRole_409() throws Exception {
        String token = login("admin@lifecycle.test");

        mockMvc.perform(post("/api/v1/admin/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "legacy",
                                "name", "Legacy Reborn"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void createRole_invalidCode_400() throws Exception {
        String token = login("admin@lifecycle.test");

        for (String code : List.of("1INVALID", "BAD CODE", "A", "   ")) {
            mockMvc.perform(post("/api/v1/admin/roles")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "code", code,
                                    "name", "Invalid Role"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    void deleteSystemRole_409() throws Exception {
        String token = login("admin@lifecycle.test");

        mockMvc.perform(delete("/api/v1/admin/roles/" + adminRole.getRoleId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void deleteRoleAssignedToActiveUser_409() throws Exception {
        String token = login("admin@lifecycle.test");

        mockMvc.perform(delete("/api/v1/admin/roles/" + assignedRole.getRoleId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void deleteRoleAssignedToDeletedUser_409() throws Exception {
        String token = login("admin@lifecycle.test");

        mockMvc.perform(delete("/api/v1/admin/roles/" + deletedUserRole.getRoleId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void deleteUnusedCustomRole_returns200AndHidesRoleButKeepsGrants() throws Exception {
        String token = login("admin@lifecycle.test");
        String roleId = unusedRole.getRoleId();
        long grantsBefore = rolePermissionCount(unusedRole.getId());
        assertTrue(grantsBefore > 0);

        mockMvc.perform(delete("/api/v1/admin/roles/" + roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        MvcResult listResult = mockMvc.perform(get("/api/v1/admin/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode roles = objectMapper.readTree(listResult.getResponse().getContentAsString())
                .path("data");
        List<String> roleIds = new ArrayList<>();
        roles.forEach(node -> roleIds.add(node.path("roleId").asText()));
        assertFalse(roleIds.contains(roleId));

        mockMvc.perform(get("/api/v1/admin/roles/" + roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        org.junit.jupiter.api.Assertions.assertEquals(grantsBefore,
                rolePermissionCount(unusedRole.getId()));
    }

    @Test
    void deleteUnknownRole_404() throws Exception {
        String token = login("admin@lifecycle.test");

        mockMvc.perform(delete("/api/v1/admin/roles/ROLE_missing")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private long rolePermissionCount(Long roleSurrogateId) {
        return ((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM role_permissions WHERE role_id = :roleId")
                .setParameter("roleId", roleSurrogateId)
                .getSingleResult()).longValue();
    }

    private Role role(String code, String name, boolean system, boolean deleted) {
        return roleRepository.findByCodeIgnoreCase(code).orElseGet(() -> roleRepository.save(
                Role.builder()
                        .code(code)
                        .name(name)
                        .system(system)
                        .deleted(deleted)
                        .build()));
    }

    private Permission permission(String code, String name, String resource, String action) {
        return permissionRepository.findByCode(code).orElseGet(() -> permissionRepository.save(
                Permission.builder()
                        .code(code)
                        .name(name)
                        .resource(resource)
                        .action(action)
                        .build()));
    }

    private void grant(Role role, Permission permission) {
        boolean alreadyGranted = rolePermissionRepository.findByRole_RoleId(role.getRoleId()).stream()
                .anyMatch(rp -> rp.getPermission().getCode().equals(permission.getCode()));
        if (!alreadyGranted) {
            rolePermissionRepository.save(RolePermission.builder()
                    .role(role)
                    .permission(permission)
                    .build());
        }
    }

    private User user(String email, Role role, UserStatus status) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(role)
                .status(status)
                .build());
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }
}
