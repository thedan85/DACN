package com.project.dacn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.dto.request.auth.LoginRequest;
import com.project.dacn.entity.auth.Permission;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.identity.Admin;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.identity.Employee;
import com.project.dacn.repository.auth.PermissionRepository;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.identity.AdminRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.identity.EmployeeRepository;
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
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminRoleControllerTest {

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
    private CustomerRepository customerRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private Role adminRole;
    private Role employeeRole;
    private Role customerRole;
    private Role customRole;
    private Role deletedRole;

    @BeforeEach
    void seedRolesAndUsers() {
        adminRole = role("ADMIN", "Administrator", true, false);
        employeeRole = role("EMPLOYEE", "Employee", true, false);
        customerRole = role("CUSTOMER", "Customer", true, false);
        customRole = role("AUDITOR", "Auditor", false, false);
        deletedRole = role("LEGACY", "Legacy Role", true, true);

        Permission roleRead = permission("role:read", "Read Role", "role", "read");
        Permission roleCreate = permission("role:create", "Create Role", "role", "create");
        Permission roleUpdate = permission("role:update", "Update Role", "role", "update");
        Permission roleDelete = permission("role:delete", "Delete Role", "role", "delete");
        Permission userRead = permission("user:read", "Read User", "user", "read");
        Permission productCreate = permission("product:create", "Create Product", "product", "create");

        // Inserted in an order that is not sorted, to prove the endpoint sorts.
        grant(adminRole, roleUpdate);
        grant(adminRole, productCreate);
        grant(adminRole, roleCreate);
        grant(adminRole, roleRead);
        grant(adminRole, roleDelete);
        grant(customRole, userRead);

        User adminActor = user("admin@rbac.test", adminRole, UserStatus.ACTIVE);
        adminProfile(adminActor, "Admin Actor", "0900000001", "Admin HQ");

        User employeeActor = user("employee@rbac.test", employeeRole, UserStatus.ACTIVE);
        employeeProfile(employeeActor, "Employee User", "0900000002", "Employee Desk");

        User customerActor = user("customer@rbac.test", customerRole, UserStatus.ACTIVE);
        customerProfile(customerActor, "Customer User", "0900000003", "Customer Home");

        entityManager.flush();
    }

    @Test
    void allGetEndpoints_withoutToken_return401() throws Exception {
        for (String path : List.of(
                "/api/v1/admin/permissions",
                "/api/v1/admin/roles",
                "/api/v1/admin/roles/" + adminRole.getRoleId())) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Test
    void customerAndPermissionlessEmployee_forbiddenOnAllGetEndpoints() throws Exception {
        String customerToken = login("customer@rbac.test");
        String employeeToken = login("employee@rbac.test");

        for (String token : new String[]{customerToken, employeeToken}) {
            for (String path : List.of(
                    "/api/v1/admin/permissions",
                    "/api/v1/admin/roles",
                    "/api/v1/admin/roles/" + adminRole.getRoleId())) {
                mockMvc.perform(get(path)
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value(403));
            }
        }
    }

    @Test
    void admin_canReadPermissionsRolesAndRoleDetail() throws Exception {
        String token = login("admin@rbac.test");

        for (String path : List.of(
                "/api/v1/admin/permissions",
                "/api/v1/admin/roles",
                "/api/v1/admin/roles/" + adminRole.getRoleId())) {
            mockMvc.perform(get(path)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Test
    void getPermissions_sortedByResourceActionCode_andIncludesEveryRolePermission() throws Exception {
        String token = login("admin@rbac.test");

        JsonNode data = data(getJson("/api/v1/admin/permissions", token));
        List<String> actual = new ArrayList<>();
        data.forEach(node -> actual.add(
                node.path("resource").asText() + "|"
                        + node.path("action").asText() + "|"
                        + node.path("code").asText()));

        List<String> expected = new ArrayList<>(actual);
        expected.sort(Comparator.naturalOrder());

        assertEquals(expected, actual, "permissions must be sorted by resource, action, code");
        assertTrue(actual.stream().anyMatch(entry -> entry.endsWith("|role:read")));
        assertTrue(actual.stream().anyMatch(entry -> entry.endsWith("|role:create")));
        assertTrue(actual.stream().anyMatch(entry -> entry.endsWith("|role:update")));
        assertTrue(actual.stream().anyMatch(entry -> entry.endsWith("|role:delete")));
    }

    @Test
    void getRoles_excludesSoftDeletedRoles_andExposesSystemFlag() throws Exception {
        String token = login("admin@rbac.test");

        JsonNode data = data(getJson("/api/v1/admin/roles", token));

        List<String> actual = new ArrayList<>();
        data.forEach(node -> actual.add(
                (node.path("system").asBoolean() ? "0" : "1") + "|" + node.path("code").asText()));

        List<String> expected = new ArrayList<>(actual);
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, actual, "roles must be sorted by system DESC, code ASC");

        JsonNode admin = findRole(data, adminRole.getRoleId());
        assertNotNull(admin, "the admin role must be listed");
        assertTrue(admin.path("system").asBoolean());

        JsonNode custom = findRole(data, customRole.getRoleId());
        assertNotNull(custom, "the custom role must be listed");
        assertFalse(custom.path("system").asBoolean());

        assertEquals(null, findRole(data, deletedRole.getRoleId()),
                "soft-deleted roles must not be listed");
    }

    @Test
    void getRole_returnsPermissionsSortedByCode() throws Exception {
        String token = login("admin@rbac.test");

        JsonNode data = data(getJson("/api/v1/admin/roles/" + adminRole.getRoleId(), token));

        assertEquals(adminRole.getRoleId(), data.path("roleId").asText());
        assertEquals("ADMIN", data.path("code").asText());
        assertTrue(data.path("system").asBoolean());

        List<String> actual = new ArrayList<>();
        data.path("permissions").forEach(node -> actual.add(node.path("code").asText()));

        List<String> expected = new ArrayList<>(actual);
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, actual, "detail permissions must be sorted by code");
        assertEquals(List.of(
                "product:create",
                "role:create",
                "role:delete",
                "role:read",
                "role:update"), actual);
    }

    @Test
    void getRole_unknownOrSoftDeleted_returns404() throws Exception {
        String token = login("admin@rbac.test");

        for (String roleId : List.of("ROLE_does_not_exist", deletedRole.getRoleId())) {
            mockMvc.perform(get("/api/v1/admin/roles/" + roleId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404));
        }
    }

    @Test
    void roleResponses_doNotExposeDeletedFlagOrInternalId() throws Exception {
        String token = login("admin@rbac.test");

        mockMvc.perform(get("/api/v1/admin/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].deleted").doesNotExist())
                .andExpect(jsonPath("$.data[0].id").doesNotExist())
                .andExpect(content().string(not(containsString("\"deleted\""))))
                .andExpect(content().string(not(containsString("passwordHash"))));

        mockMvc.perform(get("/api/v1/admin/roles/" + adminRole.getRoleId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").doesNotExist())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(content().string(not(containsString("\"deleted\""))))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    private MvcResult getJson(String path, String token) throws Exception {
        return mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode findRole(JsonNode data, String roleId) {
        for (JsonNode node : data) {
            if (roleId.equals(node.path("roleId").asText())) {
                return node;
            }
        }
        return null;
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

    private void customerProfile(User user, String name, String phone, String address) {
        customerRepository.save(Customer.builder()
                .user(user)
                .name(name)
                .phone(phone)
                .address(address)
                .build());
    }

    private void employeeProfile(User user, String name, String phone, String address) {
        employeeRepository.save(Employee.builder()
                .user(user)
                .name(name)
                .phone(phone)
                .address(address)
                .build());
    }

    private void adminProfile(User user, String name, String phone, String address) {
        adminRepository.save(Admin.builder()
                .user(user)
                .name(name)
                .phone(phone)
                .address(address)
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
