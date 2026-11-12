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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminUserControllerTest {

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
    private User eve;
    private User deletedUser;

    @BeforeEach
    void seedUsers() {
        adminRole = role("ADMIN", "Administrator");
        Role employeeRole = role("EMPLOYEE", "Employee");
        Role customerRole = role("CUSTOMER", "Customer");

        Permission readUsers = permission("user:read", "Read User", "user", "read");
        Permission createProducts = permission("product:create", "Create Product", "product", "create");
        grant(adminRole, readUsers);
        grant(adminRole, createProducts);

        User adminActor = user("admin@test.com", adminRole, UserStatus.ACTIVE);
        adminProfile(adminActor, "Admin Actor", "0900000001", "Admin HQ");

        User employeeActor = user("employee@test.com", employeeRole, UserStatus.ACTIVE);
        employeeProfile(employeeActor, "Employee User", "0900000002", "Employee Desk");

        User customerActor = user("customer@test.com", customerRole, UserStatus.ACTIVE);
        customerProfile(customerActor, "Customer User", "0900000003", "Customer Home");

        User alice = user("alice@test.com", customerRole, UserStatus.ACTIVE);
        customerProfile(alice, "Alice Customer", "0900000004", "Alice Home");

        User bob = user("bob@test.com", employeeRole, UserStatus.LOCKED);
        employeeProfile(bob, "Bob Locked", "0900000005", "Bob Desk");

        User carol = user("carol@test.com", adminRole, UserStatus.ACTIVE);
        adminProfile(carol, "Carol Admin", "0900000006", "Carol Office");

        eve = user("eve@test.com", adminRole, UserStatus.ACTIVE);
        employeeProfile(eve, "Old Employee", "0111111111", "Old Address");
        adminProfile(eve, "Eve Admin", "0999999999", "New Address");

        deletedUser = user("deleted@test.com", customerRole, UserStatus.DELETED);
        customerProfile(deletedUser, "Deleted User", "0900000007", "Deleted Home");

        entityManager.flush();
    }

    @Test
    void list_withoutToken_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void detail_withoutToken_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/" + eve.getUserId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void customerAndEmployeeWithoutUserRead_forbiddenOnBothEndpoints() throws Exception {
        String customerToken = login("customer@test.com");
        String employeeToken = login("employee@test.com");

        for (String token : new String[]{customerToken, employeeToken}) {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));

            mockMvc.perform(get("/api/v1/admin/users/" + eve.getUserId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    @Test
    void admin_canListUsers_withoutPasswordHash() throws Exception {
        String token = login("admin@test.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(7))
                .andExpect(jsonPath("$.data.content.length()").value(7))
                .andExpect(jsonPath("$.data.content[*].email", hasItem("admin@test.com")))
                .andExpect(jsonPath("$.data.content[*].roleCode", hasItem("admin")))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void admin_canViewDetail_withPermissionsAndCurrentRoleProfile() throws Exception {
        String token = login("admin@test.com");

        mockMvc.perform(get("/api/v1/admin/users/" + eve.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(eve.getUserId()))
                .andExpect(jsonPath("$.data.email").value("eve@test.com"))
                .andExpect(jsonPath("$.data.name").value("Eve Admin"))
                .andExpect(jsonPath("$.data.phone").value("0999999999"))
                .andExpect(jsonPath("$.data.address").value("New Address"))
                .andExpect(jsonPath("$.data.roleCode").value("admin"))
                .andExpect(jsonPath("$.data.roleName").value("Administrator"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.permissions",
                        contains("product:create", "user:read")))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void list_filtersByKeywordCurrentRoleNameStatusAndRoleCode() throws Exception {
        String token = login("admin@test.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("keyword", "ALICE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("alice@test.com"));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("keyword", "Carol Admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("carol@test.com"));

        // Eve's old employee profile must not answer the keyword; only her
        // current admin profile does.
        mockMvc.perform(get("/api/v1/admin/users")
                        .param("keyword", "Old Employee")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("keyword", "Eve Admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("eve@test.com"));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("status", "LOCKED")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("bob@test.com"))
                .andExpect(jsonPath("$.data.content[0].status").value("LOCKED"));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("roleCode", "ADMIN")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[*].roleCode", everyItem(is("admin"))));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("roleCode", "not-a-role")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    void list_treatsSqlLikeWildcardsInKeywordLiterally() throws Exception {
        String token = login("admin@test.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("keyword", "%")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("keyword", "_")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void list_paginatesAndAcceptsWhitelistedSort() throws Exception {
        String token = login("admin@test.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "email,asc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(7))
                .andExpect(jsonPath("$.data.totalPages").value(4))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].email").value("admin@test.com"))
                .andExpect(jsonPath("$.data.content[1].email").value("alice@test.com"));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "email,asc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("bob@test.com"))
                .andExpect(jsonPath("$.data.content[1].email").value("carol@test.com"));
    }

    @Test
    void list_rejectsUnknownSortField() throws Exception {
        String token = login("admin@test.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("sort", "passwordHash,asc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void list_rejectsDeletedStatusFilter() throws Exception {
        String token = login("admin@test.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("status", "DELETED")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void deletedUsers_areHiddenFromListAndDetail() throws Exception {
        String token = login("admin@test.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("keyword", "deleted")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content.length()").value(0));

        mockMvc.perform(get("/api/v1/admin/users/" + deletedUser.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void listAndDetail_customRoleUserShowEmployeeProfile() throws Exception {
        Role supportRole = customRole("SUPPORT", "Support");
        User supportUser = user("support@test.com", supportRole, UserStatus.ACTIVE);
        employeeProfile(supportUser, "Support Staff", "0900000008", "Support Desk");
        entityManager.flush();

        String token = login("admin@test.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("keyword", "Support Staff")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("support@test.com"))
                .andExpect(jsonPath("$.data.content[0].name").value("Support Staff"))
                .andExpect(jsonPath("$.data.content[0].phone").value("0900000008"))
                .andExpect(jsonPath("$.data.content[0].roleCode").value("support"));

        mockMvc.perform(get("/api/v1/admin/users/" + supportUser.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Support Staff"))
                .andExpect(jsonPath("$.data.phone").value("0900000008"))
                .andExpect(jsonPath("$.data.address").value("Support Desk"))
                .andExpect(jsonPath("$.data.roleCode").value("support"))
                .andExpect(jsonPath("$.data.roleName").value("Support"));
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

    private Role customRole(String code, String name) {
        return roleRepository.findByCodeIgnoreCase(code).orElseGet(() -> roleRepository.save(
                Role.builder()
                        .code(code)
                        .name(name)
                        .system(false)
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
