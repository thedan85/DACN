package com.project.dacn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.dto.request.auth.LoginRequest;
import com.project.dacn.dto.request.auth.RegisterRequest;
import com.project.dacn.dto.response.auth.LoginResponse;
import com.project.dacn.entity.auth.Permission;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.PermissionRepository;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.RateLimiter;
import com.project.dacn.service.catalog.CatalogCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RbacIntegrationTest {

    @MockitoBean
    CatalogCache cache;

    @MockitoBean
    RateLimiter rateLimiter;

    @Autowired
    private MockMvc mockMvc;

    // Local instance: request/response DTOs used here contain only Strings/longs.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void seedRbac() {
        // Profile test has no V8 seed: create roles/permissions here
        Role customerRole = roleRepository.findByCodeIgnoreCase("CUSTOMER").orElseGet(() ->
                roleRepository.save(Role.builder()
                        .code("CUSTOMER")
                        .name("Customer")
                        .system(true)
                        .deleted(false)
                        .build()));

        Role employeeRole = roleRepository.findByCodeIgnoreCase("EMPLOYEE").orElseGet(() ->
                roleRepository.save(Role.builder()
                        .code("EMPLOYEE")
                        .name("Employee")
                        .system(true)
                        .deleted(false)
                        .build()));

        Role adminRole = roleRepository.findByCodeIgnoreCase("ADMIN").orElseGet(() ->
                roleRepository.save(Role.builder()
                        .code("ADMIN")
                        .name("Administrator")
                        .system(true)
                        .deleted(false)
                        .build()));

        Permission createProduct = permissionRepository.findByCode("product:create").orElseGet(() ->
                permissionRepository.save(Permission.builder()
                        .code("product:create")
                        .name("Create Product")
                        .resource("product")
                        .action("create")
                        .build()));

        // Grant product:create to EMPLOYEE only (CUSTOMER stays read-only)
        rolePermissionRepository.findByRole_RoleId(employeeRole.getRoleId()).stream()
                .filter(rp -> rp.getPermission().getCode().equals("product:create"))
                .findFirst()
                .orElseGet(() -> rolePermissionRepository.save(RolePermission.builder()
                        .role(employeeRole)
                        .permission(createProduct)
                        .build()));

        // Mirrors V21: order:complete is granted to ADMIN only.
        Permission completeOrder = permissionRepository.findByCode("order:complete").orElseGet(() ->
                permissionRepository.save(Permission.builder()
                        .code("order:complete")
                        .name("Complete Order")
                        .resource("order")
                        .action("complete")
                        .build()));
        rolePermissionRepository.findByRole_RoleId(adminRole.getRoleId()).stream()
                .filter(rp -> rp.getPermission().getCode().equals("order:complete"))
                .findFirst()
                .orElseGet(() -> rolePermissionRepository.save(RolePermission.builder()
                        .role(adminRole)
                        .permission(completeOrder)
                        .build()));
    }

    @Test
    void publicEndpoint_noToken_200() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_noToken_401() throws Exception {
        mockMvc.perform(get("/api/v1/test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void protectedEndpoint_customerToken_403() throws Exception {
        // Register CUSTOMER user
        RegisterRequest registerRequest = new RegisterRequest(
                "customer@test.com",
                "password123",
                "Test Customer",
                "1234567890"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Login to get token
        LoginRequest loginRequest = new LoginRequest("customer@test.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String loginResponseJson = loginResult.getResponse().getContentAsString();
        LoginResponse loginResponse = objectMapper.readTree(loginResponseJson)
                .get("data")
                .traverse(objectMapper)
                .readValueAs(LoginResponse.class);

        // GET /test/protected with CUSTOMER token (lacks product:create) -> 403
        mockMvc.perform(get("/api/v1/test/protected")
                        .header("Authorization", "Bearer " + loginResponse.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // CUSTOMER may read the catalog but must not write to it.
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + loginResponse.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpoint_employeeToken_200() throws Exception {
        // Create EMPLOYEE user directly via repository (register only creates CUSTOMER)
        Role employeeRole = roleRepository.findByCodeIgnoreCase("EMPLOYEE").orElseThrow();

        User employee = User.builder()
                .email("employee@test.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(employeeRole)
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(employee);

        // Login to get token
        LoginRequest loginRequest = new LoginRequest("employee@test.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String loginResponseJson = loginResult.getResponse().getContentAsString();
        LoginResponse loginResponse = objectMapper.readTree(loginResponseJson)
                .get("data")
                .traverse(objectMapper)
                .readValueAs(LoginResponse.class);

        // GET /test/protected with EMPLOYEE token (has product:create) -> 200
        mockMvc.perform(get("/api/v1/test/protected")
                        .header("Authorization", "Bearer " + loginResponse.accessToken()))
                .andExpect(status().isOk());

        // EMPLOYEE passes the security rule; the empty body then fails validation (400).
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + loginResponse.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void orderCompletion_employeeToken_403() throws Exception {
        Role employeeRole = roleRepository.findByCodeIgnoreCase("EMPLOYEE").orElseThrow();
        User employee = userRepository.save(User.builder()
                .email("complete-employee@test.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(employeeRole)
                .status(UserStatus.ACTIVE)
                .build());

        // EMPLOYEE has order:update but must not gain order:complete.
        mockMvc.perform(post("/api/v1/admin/orders/ORD_1/complete")
                        .header("Authorization", "Bearer " + login(employee.getEmail())))
                .andExpect(status().isForbidden());
    }

    @Test
    void orderCompletion_adminToken_404() throws Exception {
        Role adminRole = roleRepository.findByCodeIgnoreCase("ADMIN").orElseThrow();
        User admin = userRepository.save(User.builder()
                .email("complete-admin@test.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(adminRole)
                .status(UserStatus.ACTIVE)
                .build());

        // Authorization passes; the unknown order is then a 404.
        mockMvc.perform(post("/api/v1/admin/orders/ORD_1/complete")
                        .header("Authorization", "Bearer " + login(admin.getEmail())))
                .andExpect(status().isNotFound());
    }

    private String login(String email) throws Exception {
        LoginRequest loginRequest = new LoginRequest(email, "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }
}
