package com.project.dacn;

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
import com.project.dacn.security.RefreshTokenCookie;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminUserMutationControllerTest {

    private static final String PASSWORD = "password123";
    private static final String NEW_PASSWORD = "newpassword123";

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
    private User adminActor;
    private User secondAdmin;
    private User employeeUser;
    private User customerUser;
    private User deletedUser;

    @BeforeEach
    void seedUsers() {
        adminRole = role("ADMIN", "Administrator");
        employeeRole = role("EMPLOYEE", "Employee");
        customerRole = role("CUSTOMER", "Customer");

        Permission readUsers = permission("user:read", "Read User", "user", "read");
        Permission createUsers = permission("user:create", "Create User", "user", "create");
        Permission updateUsers = permission("user:update", "Update User", "user", "update");
        Permission deleteUsers = permission("user:delete", "Delete User", "user", "delete");

        grant(adminRole, readUsers);
        grant(adminRole, createUsers);
        grant(adminRole, updateUsers);
        grant(adminRole, deleteUsers);

        // The employee actor is allowed to exercise the last-admin guard on
        // the two mutation endpoints that require these grants.
        grant(employeeRole, updateUsers);
        grant(employeeRole, deleteUsers);

        adminActor = user("admin@test.com", adminRole, UserStatus.ACTIVE);
        adminProfile(adminActor, "Admin Actor", "0900000001", "Admin HQ");

        secondAdmin = user("second-admin@test.com", adminRole, UserStatus.ACTIVE);
        adminProfile(secondAdmin, "Second Admin", "0900000002", "Second HQ");

        employeeUser = user("employee@test.com", employeeRole, UserStatus.ACTIVE);
        employeeProfile(employeeUser, "Employee User", "0900000003", "Employee Desk");

        customerUser = user("customer@test.com", customerRole, UserStatus.ACTIVE);
        customerProfile(customerUser, "Customer User", "0900000004", "Customer Home");

        deletedUser = user("deleted@test.com", customerRole, UserStatus.DELETED);
        customerProfile(deletedUser, "Deleted User", "0900000005", "Deleted Home");

        entityManager.flush();
    }

    @Test
    void mutations_withoutToken_401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("no-token@test.com", "employee")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"admin\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(delete("/api/v1/admin/users/" + employeeUser.getUserId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void mutations_customerWithoutPermission_403() throws Exception {
        String token = login("customer@test.com").accessToken();

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("forbidden@test.com", "employee")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/role")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(delete("/api/v1/admin/users/" + employeeUser.getUserId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void post_permissionlessEmployee_403() throws Exception {
        String token = permissionlessEmployeeToken();

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("forbidden-employee@test.com", "employee")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void status_permissionlessEmployee_403() throws Exception {
        String token = permissionlessEmployeeToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void role_permissionlessEmployee_403() throws Exception {
        String token = permissionlessEmployeeToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/role")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void delete_permissionlessEmployee_403() throws Exception {
        String token = permissionlessEmployeeToken();

        mockMvc.perform(delete("/api/v1/admin/users/" + employeeUser.getUserId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void createEmployee_201_createsEmployeeProfileAndHashesPassword() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("new-employee@test.com", "employee")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.message").value("Created"))
                .andExpect(jsonPath("$.data.email").value("new-employee@test.com"))
                .andExpect(jsonPath("$.data.roleCode").value("employee"))
                .andExpect(jsonPath("$.data.name").value("New Staff"))
                .andExpect(jsonPath("$.data.phone").value("0912345678"))
                .andExpect(jsonPath("$.data.address").value("New Address"))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString(NEW_PASSWORD))));

        User created = userRepository.findByEmail("new-employee@test.com").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(created.getPasswordHash())
                .isNotEqualTo(NEW_PASSWORD);
        org.assertj.core.api.Assertions.assertThat(passwordEncoder.matches(
                NEW_PASSWORD, created.getPasswordHash())).isTrue();

        Employee profile = employeeRepository.findByUser_UserId(created.getUserId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(profile.getName()).isEqualTo("New Staff");
        org.assertj.core.api.Assertions.assertThat(profile.getPhone()).isEqualTo("0912345678");
        org.assertj.core.api.Assertions.assertThat(profile.getAddress()).isEqualTo("New Address");
    }

    @Test
    void createAdmin_201_createsAdminProfileAndHashesPassword() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("new-admin@test.com", "admin")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.roleCode").value("admin"))
                .andExpect(jsonPath("$.data.name").value("New Staff"))
                .andExpect(jsonPath("$.data.permissions", containsInAnyOrder(
                        "user:read", "user:create", "user:update", "user:delete")))
                .andExpect(content().string(not(containsString("passwordHash"))));

        User created = userRepository.findByEmail("new-admin@test.com").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(passwordEncoder.matches(
                NEW_PASSWORD, created.getPasswordHash())).isTrue();

        Admin profile = adminRepository.findByUser_UserId(created.getUserId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(profile.getName()).isEqualTo("New Staff");
        org.assertj.core.api.Assertions.assertThat(profile.getPhone()).isEqualTo("0912345678");
        org.assertj.core.api.Assertions.assertThat(profile.getAddress()).isEqualTo("New Address");
    }

    @Test
    void create_duplicateEmailDifferentCase_409() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("ADMIN@TEST.COM", "employee")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    @Test
    void create_softDeletedEmail_409() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("deleted@test.com", "employee")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    @Test
    void create_invalidInput_400() throws Exception {
        String token = login("admin@test.com").accessToken();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", "");
        body.put("password", "short");
        body.put("name", "");
        body.put("phone", "123");
        body.put("address", "");
        body.put("roleCode", "");

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void create_customerRole_409() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("customer-role@test.com", "customer")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Invalid role; only employee and admin are supported"));
    }

    @Test
    void lock_revokesRefreshTokensAndBlocksLogin_thenUnlockAllowsLogin() throws Exception {
        Session employeeSession = login("employee@test.com");
        String adminToken = login("admin@test.com").accessToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/status")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("LOCKED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("employee@test.com", PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, employeeSession.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", bearer(employeeSession.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/status")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        login("employee@test.com");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, employeeSession.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void status_invalid_409() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Invalid status; only ACTIVE and LOCKED are supported"));

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_A_STATUS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Invalid status; only ACTIVE and LOCKED are supported"));
    }

    @Test
    void role_employeeToAdmin_sameAccessTokenSeesAdminPermissions() throws Exception {
        Session employeeSession = login("employee@test.com");
        String adminToken = login("admin@test.com").accessToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/role")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.roleCode").value("admin"))
                .andExpect(jsonPath("$.data.roleName").value("Administrator"))
                .andExpect(jsonPath("$.data.name").value("Employee User"))
                .andExpect(jsonPath("$.data.phone").value("0900000003"))
                .andExpect(jsonPath("$.data.address").value("Employee Desk"))
                .andExpect(jsonPath("$.data.permissions", containsInAnyOrder(
                        "user:read", "user:create", "user:update", "user:delete")));

        org.assertj.core.api.Assertions.assertThat(
                employeeRepository.findByUser_UserId(employeeUser.getUserId())).isPresent();
        Admin copiedProfile = adminRepository.findByUser_UserId(employeeUser.getUserId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(copiedProfile.getName()).isEqualTo("Employee User");
        org.assertj.core.api.Assertions.assertThat(copiedProfile.getPhone()).isEqualTo("0900000003");
        org.assertj.core.api.Assertions.assertThat(copiedProfile.getAddress()).isEqualTo("Employee Desk");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", bearer(employeeSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCode").value("ADMIN"))
                .andExpect(jsonPath("$.data.roleName").value("Administrator"))
                .andExpect(jsonPath("$.data.permissions", containsInAnyOrder(
                        "user:read", "user:create", "user:update", "user:delete")));
    }

    @Test
    void role_customer_409() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + customerUser.getUserId() + "/role")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"employee\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Customer role cannot be changed"));
    }

    @Test
    void role_invalid_409() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + employeeUser.getUserId() + "/role")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"customer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Invalid role; only employee and admin are supported"));
    }

    @Test
    void selfLockSelfDeleteSelfDemote_409() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + adminActor.getUserId() + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("You cannot lock your own account"));

        mockMvc.perform(patch("/api/v1/admin/users/" + adminActor.getUserId() + "/role")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"employee\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(
                        "You cannot demote your own administrator account"));

        mockMvc.perform(delete("/api/v1/admin/users/" + adminActor.getUserId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("You cannot delete your own account"));
    }

    @Test
    void lastActiveAdmin_cannotBeLockedDeletedOrDemoted_409() throws Exception {
        String adminToken = login("admin@test.com").accessToken();
        String employeeToken = login("employee@test.com").accessToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + secondAdmin.getUserId() + "/status")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/users/" + adminActor.getUserId() + "/status")
                        .header("Authorization", bearer(employeeToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Cannot modify the last active administrator"));

        mockMvc.perform(patch("/api/v1/admin/users/" + adminActor.getUserId() + "/role")
                        .header("Authorization", bearer(employeeToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"employee\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Cannot modify the last active administrator"));

        mockMvc.perform(delete("/api/v1/admin/users/" + adminActor.getUserId())
                        .header("Authorization", bearer(employeeToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Cannot modify the last active administrator"));
    }

    @Test
    void softDelete_hidesUserRevokesTokensAndBlocksLogin() throws Exception {
        Session employeeSession = login("employee@test.com");
        String adminToken = login("admin@test.com").accessToken();

        mockMvc.perform(delete("/api/v1/admin/users/" + employeeUser.getUserId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("keyword", "employee@test.com")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get("/api/v1/admin/users/" + employeeUser.getUserId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("employee@test.com", PASSWORD))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, employeeSession.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", bearer(employeeSession.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void deletedTarget_mutations_404() throws Exception {
        String token = login("admin@test.com").accessToken();

        mockMvc.perform(patch("/api/v1/admin/users/" + deletedUser.getUserId() + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(patch("/api/v1/admin/users/" + deletedUser.getUserId() + "/role")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"employee\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete("/api/v1/admin/users/" + deletedUser.getUserId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private String createBody(String email, String roleCode) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", NEW_PASSWORD);
        body.put("name", "New Staff");
        body.put("phone", "0912345678");
        body.put("address", "New Address");
        body.put("roleCode", roleCode);
        return objectMapper.writeValueAsString(body);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private Session login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new Session(data.path("accessToken").asText(), data.path("refreshToken").asText());
    }

    private String permissionlessEmployeeToken() throws Exception {
        // Separate employee actor whose role has no user:* grants. The seeded
        // employee role intentionally has user:update/user:delete for the
        // last-admin tests, so it cannot be reused here.
        Role permissionlessEmployeeRole = role(
                "EMPLOYEE_NO_USER_PERMISSIONS", "Employee Without User Permissions");
        User permissionlessEmployee = user(
                "employee-no-access@test.com", permissionlessEmployeeRole, UserStatus.ACTIVE);
        employeeProfile(permissionlessEmployee,
                "Employee No Access", "0900000008", "No Access Desk");
        entityManager.flush();
        return login("employee-no-access@test.com").accessToken();
    }

    private Role role(String code, String name) {
        return roleRepository.findByCodeIgnoreCase(code).orElseGet(() -> roleRepository.save(
                Role.builder()
                        .code(code)
                        .name(name)
                        .system(true)
                        .deleted(false)
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

    private record Session(String accessToken, String refreshToken) {
    }
}
