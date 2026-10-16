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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
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
    }

    @Test
    void publicEndpoint_noToken_200() throws Exception {
        mockMvc.perform(get("/api/v1/test/public"))
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
    }
}
