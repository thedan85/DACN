package com.project.dacn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.dto.request.auth.LoginRequest;
import com.project.dacn.entity.auth.Permission;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.identity.Admin;
import com.project.dacn.entity.identity.Employee;
import com.project.dacn.repository.auth.PermissionRepository;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.identity.AdminRepository;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Privilege-escalation regression coverage for the admin user/role endpoints.
 *
 * The fixtures deliberately give non-admin actors only the permission required
 * by the endpoint under test, so a passing 200 cannot be explained by the
 * actor already being an administrator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminAuthorityPolicyControllerTest {

    private static final String PASSWORD = "password123";
    private static final String DENIED = "You don't have permission to assign this role";

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
    private AdminRepository adminRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private Role adminRole;
    private Role employeeRole;
    private Role supportRole;
    private Role emptyRole;
    private Permission userCreate;
    private Permission userUpdate;
    private Permission userDelete;
    private Permission roleUpdate;
    private Permission productCreate;
    private User adminActor;
    private User secondAdmin;
    private User employeeUser;
    private User updater;

    @BeforeEach
    void seedFixtures() {
        adminRole = role("ADMIN", "Administrator", true);
        employeeRole = role("EMPLOYEE", "Employee", true);
        role("CUSTOMER", "Customer", true);
        supportRole = role("SUPPORT", "Support", false);
        emptyRole = role("REPORTER", "Reporter", false);

        Permission userRead = permission("user:read", "Read User", "user", "read");
        userCreate = permission("user:create", "Create User", "user", "create");
        userUpdate = permission("user:update", "Update User", "user", "update");
        userDelete = permission("user:delete", "Delete User", "user", "delete");
        Permission roleRead = permission("role:read", "Read Role", "role", "read");
        Permission roleCreate = permission("role:create", "Create Role", "role", "create");
        roleUpdate = permission("role:update", "Update Role", "role", "update");
        Permission roleDelete = permission("role:delete", "Delete Role", "role", "delete");
        productCreate = permission("product:create", "Create Product", "product", "create");

        grant(adminRole, userRead);
        grant(adminRole, userCreate);
        grant(adminRole, userUpdate);
        grant(adminRole, userDelete);
        grant(adminRole, roleRead);
        grant(adminRole, roleCreate);
        grant(adminRole, roleUpdate);
        grant(adminRole, roleDelete);

        // Non-admin actors: only the endpoint permission plus one innocuous
        // permission that the role-editing test can grant onward.
        Role creatorRole = role("CREATOR", "Creator", false);
        grant(creatorRole, userCreate);

        Role updaterRole = role("UPDATER", "Updater", false);
        grant(updaterRole, userUpdate);

        Role deleterRole = role("DELETER", "Deleter", false);
        grant(deleterRole, userDelete);

        Role roleEditorRole = role("ROLE_EDITOR", "Role Editor", false);
        grant(roleEditorRole, roleUpdate);
        grant(roleEditorRole, productCreate);

        // The updater fully holds this role's only permission, so assigning it
        // is allowed.
        grant(supportRole, userUpdate);

        adminActor = user("admin@authz.test", adminRole);
        adminProfile(adminActor, "Admin Actor", "0900000001", "Admin HQ");

        secondAdmin = user("second-admin@authz.test", adminRole);
        adminProfile(secondAdmin, "Second Admin", "0900000002", "Second HQ");

        employeeUser = user("employee@authz.test", employeeRole);
        employeeProfile(employeeUser, "Employee User", "0900000003", "Employee Desk");

        user("creator@authz.test", creatorRole);
        updater = user("updater@authz.test", updaterRole);
        user("deleter@authz.test", deleterRole);
        user("role-editor@authz.test", roleEditorRole);

        entityManager.flush();
    }

    @Test
    void userCreateOnly_cannotCreateAdmin_403() throws Exception {
        String token = login("creator@authz.test");

        createUser(token, "new-admin@authz.test", "admin")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(DENIED));
    }

    @Test
    void userCreateOnly_canCreateEmployee_201() throws Exception {
        String token = login("creator@authz.test");

        createUser(token, "new-employee@authz.test", "employee")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.roleCode").value("employee"));
    }

    @Test
    void userUpdateOnly_cannotPromoteSelf_403() throws Exception {
        String token = login("updater@authz.test");

        changeRole(token, updater.getUserId(), "admin")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(DENIED));
    }

    @Test
    void userUpdateOnly_cannotPromoteAnotherUserToAdmin_403() throws Exception {
        String token = login("updater@authz.test");

        changeRole(token, employeeUser.getUserId(), "admin")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(DENIED));
    }

    @Test
    void userUpdateOnly_cannotDemoteAnAdmin_403() throws Exception {
        String token = login("updater@authz.test");

        changeRole(token, secondAdmin.getUserId(), "employee")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(DENIED));
    }

    @Test
    void userUpdateOnly_canAssignRoleWhosePermissionsItHolds_200() throws Exception {
        String token = login("updater@authz.test");

        changeRole(token, employeeUser.getUserId(), "support")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.roleCode").value("support"));
    }

    @Test
    void userUpdateOnly_cannotLockAnAdmin_403() throws Exception {
        String token = login("updater@authz.test");

        mockMvc.perform(patch("/api/v1/admin/users/" + secondAdmin.getUserId() + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(DENIED));
    }

    @Test
    void userDeleteOnly_cannotDeleteAnAdmin_403() throws Exception {
        String token = login("deleter@authz.test");

        mockMvc.perform(delete("/api/v1/admin/users/" + secondAdmin.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(DENIED));
    }

    @Test
    void roleUpdateOnly_cannotGrantItselfUserUpdate_403() throws Exception {
        String token = login("role-editor@authz.test");
        Role roleEditorRole = roleRepository.findByCodeIgnoreCase("ROLE_EDITOR").orElseThrow();

        replacePermissions(token, roleEditorRole.getRoleId(), List.of(
                        roleUpdate.getPermissionId(),
                        userUpdate.getPermissionId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(DENIED));
    }

    @Test
    void roleUpdateOnly_cannotEditAdminRole_403() throws Exception {
        String token = login("role-editor@authz.test");

        mockMvc.perform(patch("/api/v1/admin/roles/" + adminRole.getRoleId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Renamed Admin"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(DENIED));
    }

    @Test
    void roleUpdateOnly_canGrantAPermissionItHoldsToAnotherRole_200() throws Exception {
        String token = login("role-editor@authz.test");

        replacePermissions(token, emptyRole.getRoleId(), List.of(productCreate.getPermissionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.permissions[*].code", contains("product:create")));
    }

    @Test
    void admin_keepsFullAccess() throws Exception {
        String token = login("admin@authz.test");

        createUser(token, "admin-created@authz.test", "admin")
                .andExpect(status().isCreated());

        changeRole(token, employeeUser.getUserId(), "support")
                .andExpect(status().isOk());

        replacePermissions(token, emptyRole.getRoleId(), List.of(productCreate.getPermissionId()))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/roles/" + adminRole.getRoleId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Administrator"))))
                .andExpect(status().isOk());
    }

    private ResultActions createUser(String token, String email, String roleCode) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", "newpassword123");
        body.put("name", "New Staff");
        body.put("phone", "0912345678");
        body.put("address", "New Address");
        body.put("roleCode", roleCode);
        return mockMvc.perform(post("/api/v1/admin/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions changeRole(String token, String userId, String roleCode) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/users/" + userId + "/role")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("roleCode", roleCode))));
    }

    private ResultActions replacePermissions(
            String token, String roleId, List<String> permissionIds) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/roles/" + roleId + "/permissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("permissionIds", permissionIds))));
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

    private Role role(String code, String name, boolean system) {
        return roleRepository.findByCodeIgnoreCase(code).orElseGet(() -> roleRepository.save(
                Role.builder()
                        .code(code)
                        .name(name)
                        .system(system)
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

    private User user(String email, Role role) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(role)
                .status(UserStatus.ACTIVE)
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

    private void employeeProfile(User user, String name, String phone, String address) {
        employeeRepository.save(Employee.builder()
                .user(user)
                .name(name)
                .phone(phone)
                .address(address)
                .build());
    }
}
