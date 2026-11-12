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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminRoleMutationControllerTest {

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
    private Role customRole;
    private Permission roleRead;
    private Permission roleCreate;
    private Permission roleUpdate;
    private Permission roleDelete;
    private Permission userRead;
    private Permission productCreate;
    private Permission productUpdate;
    private Permission productDelete;

    @BeforeEach
    void seedRolesAndUsers() {
        adminRole = role("ADMIN", "Administrator", true, false);
        role("EMPLOYEE", "Employee", true, false);
        role("CUSTOMER", "Customer", true, false);
        customRole = role("SUPPORT", "Support", false, false);
        customRole.setDescription("Support role");

        roleRead = permission("role:read", "Read Role", "role", "read");
        roleCreate = permission("role:create", "Create Role", "role", "create");
        roleUpdate = permission("role:update", "Update Role", "role", "update");
        roleDelete = permission("role:delete", "Delete Role", "role", "delete");
        userRead = permission("user:read", "Read User", "user", "read");
        productCreate = permission("product:create", "Create Product", "product", "create");
        productUpdate = permission("product:update", "Update Product", "product", "update");
        productDelete = permission("product:delete", "Delete Product", "product", "delete");

        grant(adminRole, roleRead);
        grant(adminRole, roleCreate);
        grant(adminRole, roleUpdate);
        grant(adminRole, roleDelete);
        grant(adminRole, userRead);

        grant(customRole, productCreate);
        grant(customRole, productUpdate);

        user("admin@mutation.test", adminRole, UserStatus.ACTIVE);
        user("employee@mutation.test", roleRepository.findByCodeIgnoreCase("EMPLOYEE").orElseThrow(),
                UserStatus.ACTIVE);
        user("customer@mutation.test", roleRepository.findByCodeIgnoreCase("CUSTOMER").orElseThrow(),
                UserStatus.ACTIVE);
        user("staff@mutation.test", customRole, UserStatus.ACTIVE);

        entityManager.flush();
    }

    @Test
    void mutationEndpoints_withoutToken_return401() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/roles/" + customRole.getRoleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "New Name"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(put("/api/v1/admin/roles/" + customRole.getRoleId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("permissionIds", List.of(productCreate.getPermissionId())))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void customerAndPermissionlessEmployee_forbiddenOnMutationEndpoints() throws Exception {
        String customerToken = login("customer@mutation.test");
        String employeeToken = login("employee@mutation.test");

        for (String token : new String[]{customerToken, employeeToken}) {
            mockMvc.perform(patch("/api/v1/admin/roles/" + customRole.getRoleId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "New Name"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));

            mockMvc.perform(put("/api/v1/admin/roles/" + customRole.getRoleId() + "/permissions")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("permissionIds", List.of(productCreate.getPermissionId())))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    @Test
    void patch_updatesNameOnly() throws Exception {
        String token = login("admin@mutation.test");

        patchRole(customRole.getRoleId(), token, Map.of("name", "Support Desk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Support Desk"))
                .andExpect(jsonPath("$.data.description").value("Support role"));
    }

    @Test
    void patch_updatesDescriptionOnly() throws Exception {
        String token = login("admin@mutation.test");

        patchRole(customRole.getRoleId(), token, Map.of("description", "Handles tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Support"))
                .andExpect(jsonPath("$.data.description").value("Handles tickets"));
    }

    @Test
    void patch_blankDescriptionClearsIt() throws Exception {
        String token = login("admin@mutation.test");

        patchRole(customRole.getRoleId(), token, Map.of("description", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value(nullValue()));
    }

    @Test
    void patch_blankName_400() throws Exception {
        String token = login("admin@mutation.test");

        patchRole(customRole.getRoleId(), token, Map.of("name", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void patch_withNoFields_400() throws Exception {
        String token = login("admin@mutation.test");

        patchRole(customRole.getRoleId(), token, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void patch_doesNotChangeRoleCode() throws Exception {
        String token = login("admin@mutation.test");

        patchRole(customRole.getRoleId(), token, Map.of("name", "Support Desk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("SUPPORT"));
    }

    @Test
    void patch_unknownRole_404() throws Exception {
        String token = login("admin@mutation.test");

        patchRole("ROLE_missing", token, Map.of("name", "New Name"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void put_addsPermission() throws Exception {
        String token = login("admin@mutation.test");

        putPermissions(customRole.getRoleId(), token, List.of(
                        productCreate.getPermissionId(),
                        productUpdate.getPermissionId(),
                        productDelete.getPermissionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[*].code", contains(
                        "product:create", "product:delete", "product:update")));
    }

    @Test
    void put_removesPermission() throws Exception {
        String token = login("admin@mutation.test");

        putPermissions(customRole.getRoleId(), token, List.of(productCreate.getPermissionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[*].code", contains("product:create")));
    }

    @Test
    void put_retainsGrantedAtAndRowIdForRetainedGrants() throws Exception {
        String token = login("admin@mutation.test");
        RolePermission retained = grantOf(customRole.getRoleId(), "product:create");
        Long retainedId = retained.getId();
        Instant retainedGrantedAt = retained.getGrantedAt();

        putPermissions(customRole.getRoleId(), token, List.of(
                        productCreate.getPermissionId(),
                        productUpdate.getPermissionId(),
                        productDelete.getPermissionId()))
                .andExpect(status().isOk());

        RolePermission after = grantOf(customRole.getRoleId(), "product:create");
        org.junit.jupiter.api.Assertions.assertEquals(retainedId, after.getId());
        org.junit.jupiter.api.Assertions.assertEquals(retainedGrantedAt, after.getGrantedAt());
    }

    @Test
    void put_emptyListClearsCustomRole() throws Exception {
        String token = login("admin@mutation.test");

        putPermissions(customRole.getRoleId(), token, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions.length()").value(0));
    }

    @Test
    void put_unknownPermission_404_andLeavesGrantsUnchanged() throws Exception {
        String token = login("admin@mutation.test");

        putPermissions(customRole.getRoleId(), token, List.of(
                        productCreate.getPermissionId(),
                        "PERM_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        assertDetailCodes(customRole.getRoleId(), token, "product:create", "product:update");
    }

    @Test
    void put_duplicatePermissionIds_400_andLeavesGrantsUnchanged() throws Exception {
        String token = login("admin@mutation.test");

        putPermissions(customRole.getRoleId(), token, List.of(
                        productCreate.getPermissionId(),
                        productCreate.getPermissionId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        assertDetailCodes(customRole.getRoleId(), token, "product:create", "product:update");
    }

    @Test
    void put_adminWithoutUserRead_409() throws Exception {
        String token = login("admin@mutation.test");

        putPermissions(adminRole.getRoleId(), token, List.of(
                        roleRead.getPermissionId(),
                        roleCreate.getPermissionId(),
                        roleUpdate.getPermissionId(),
                        roleDelete.getPermissionId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void put_adminWithoutRoleRead_409() throws Exception {
        String token = login("admin@mutation.test");

        putPermissions(adminRole.getRoleId(), token, List.of(
                        roleCreate.getPermissionId(),
                        roleUpdate.getPermissionId(),
                        roleDelete.getPermissionId(),
                        userRead.getPermissionId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void put_adminWithEveryUserAndRolePermission_200() throws Exception {
        String token = login("admin@mutation.test");

        putPermissions(adminRole.getRoleId(), token, List.of(
                        roleRead.getPermissionId(),
                        roleCreate.getPermissionId(),
                        roleUpdate.getPermissionId(),
                        roleDelete.getPermissionId(),
                        userRead.getPermissionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[*].code", contains(
                        "role:create", "role:delete", "role:read", "role:update", "user:read")));
    }

    @Test
    void sameToken_losesProductCreateOnTheNextRequest() throws Exception {
        String adminToken = login("admin@mutation.test");
        String staffToken = login("staff@mutation.test");

        // The custom role currently grants product:create, so authorization
        // passes and only bean validation rejects the empty body.
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        putPermissions(customRole.getRoleId(), adminToken,
                List.of(productUpdate.getPermissionId()))
                .andExpect(status().isOk());

        // Same token, no re-login: permissions are read from the database on
        // every request, so the next call must be rejected by authorization.
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private org.springframework.test.web.servlet.ResultActions patchRole(
            String roleId, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/roles/" + roleId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions putPermissions(
            String roleId, String token, List<String> permissionIds) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/roles/" + roleId + "/permissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("permissionIds", permissionIds))));
    }

    private RolePermission grantOf(String roleId, String permissionCode) {
        return rolePermissionRepository.findByRole_RoleId(roleId).stream()
                .filter(grant -> grant.getPermission().getCode().equals(permissionCode))
                .findFirst()
                .orElseThrow();
    }

    private void assertDetailCodes(String roleId, String token, String... expected) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/roles/" + roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode permissions = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("permissions");
        List<String> actual = new ArrayList<>();
        permissions.forEach(node -> actual.add(node.path("code").asText()));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(expected), actual);
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
