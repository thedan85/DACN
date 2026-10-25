package com.project.dacn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.common.exception.AccountNotFoundException;
import com.project.dacn.common.exception.EmailAlreadyExistsException;
import com.project.dacn.common.exception.InvalidPasswordException;
import com.project.dacn.common.exception.InvalidTokenException;
import com.project.dacn.common.exception.UserLockedException;
import com.project.dacn.dto.request.auth.LoginRequest;
import com.project.dacn.dto.request.auth.RefreshRequest;
import com.project.dacn.dto.request.auth.RegisterRequest;
import com.project.dacn.dto.response.auth.LoginResponse;
import com.project.dacn.entity.auth.Permission;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.auth.PermissionRepository;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.security.RefreshTokenCookie;
import com.project.dacn.service.auth.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthServiceTest {

    @Autowired
    private MockMvc mockMvc;

    // Local instance: DTOs used here contain only Strings/longs.
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Real service, NOT mocked: exercises register/login/refresh/logout against H2.
    @Autowired
    private AuthService authService;

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

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void seedRbac() {
        // Profile test has no V8 seed: create roles/permissions here (idempotent per test).
        roleRepository.findByCodeIgnoreCase("CUSTOMER").orElseGet(() ->
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

        // Grant product:create to EMPLOYEE only (CUSTOMER stays read-only).
        rolePermissionRepository.findByRole_RoleId(employeeRole.getRoleId()).stream()
                .filter(rp -> rp.getPermission().getCode().equals("product:create"))
                .findFirst()
                .orElseGet(() -> rolePermissionRepository.save(RolePermission.builder()
                        .role(employeeRole)
                        .permission(createProduct)
                        .build()));
    }

    private RegisterRequest registerRequest(String email) {
        return new RegisterRequest(email, "password123", "Test User", "1234567890");
    }

    @Test
    void register_emailCaseInsensitive_duplicateRejected() {
        authService.register(registerRequest("User@Test.com"));

        // Stored email is normalized to lowercase.
        assertThat(userRepository.existsByEmail("user@test.com")).isTrue();

        // Same email with different casing must be treated as duplicate (-> 409 via handler).
        assertThatThrownBy(() -> authService.register(registerRequest("USER@test.com")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void refresh_rotation_oldTokenRejected() {
        authService.register(registerRequest("rotate@test.com"));
        LoginResponse login = authService.login(
                new LoginRequest("rotate@test.com", "password123"), null, null);

        // First refresh succeeds (rotation).
        var refreshed = authService.refresh(new RefreshRequest(login.refreshToken()));
        assertThat(refreshed.accessToken()).isNotNull();
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());

        // Old refresh token was revoked by rotation -> must be rejected.
        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(login.refreshToken())))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void logout_thenRefresh_rejected() {
        authService.register(registerRequest("logout@test.com"));
        LoginResponse login = authService.login(
                new LoginRequest("logout@test.com", "password123"), null, null);

        authService.logout(new RefreshRequest(login.refreshToken()));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(login.refreshToken())))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void logout_idempotent_garbageToken() {
        // Garbage token (not a JWT at all) must not throw: logout always succeeds.
        assertThatCode(() -> authService.logout(new RefreshRequest("garbage-token-not-a-jwt")))
                .doesNotThrowAnyException();

        // Logging out twice with the same valid token must not throw either.
        authService.register(registerRequest("double-logout@test.com"));
        LoginResponse login = authService.login(
                new LoginRequest("double-logout@test.com", "password123"), null, null);
        assertThatCode(() -> {
            authService.logout(new RefreshRequest(login.refreshToken()));
            authService.logout(new RefreshRequest(login.refreshToken()));
        }).doesNotThrowAnyException();
    }

    @Test
    void lockedUser_cannotLogin() {
        authService.register(registerRequest("locked@test.com"));
        User user = userRepository.findByEmail("locked@test.com").orElseThrow();
        user.setStatus(UserStatus.LOCKED);
        userRepository.save(user);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("locked@test.com", "password123"), null, null))
                .isInstanceOf(UserLockedException.class);
    }

    @Test
    void unknownEmail_cannotLogin_accountNotFound() {
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("missing@test.com", "password123"), null, null))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("The account does not exist");
    }

    @Test
    void wrongPassword_cannotLogin_invalidPassword() {
        authService.register(registerRequest("wrong-password@test.com"));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("wrong-password@test.com", "not-the-password"), null, null))
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessage("Invalid password");
    }

    @Test
    void deletedUser_cannotLogin_accountNotFound() {
        authService.register(registerRequest("deleted@test.com"));
        User user = userRepository.findByEmail("deleted@test.com").orElseThrow();
        user.setStatus(UserStatus.DELETED);
        userRepository.save(user);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("deleted@test.com", "password123"), null, null))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("The account does not exist");
    }

    @Test
    void lockedUser_cannotRefresh() {
        authService.register(registerRequest("locked-refresh@test.com"));
        LoginResponse login = authService.login(
                new LoginRequest("locked-refresh@test.com", "password123"), null, null);

        // Lock the user AFTER tokens were issued: refresh must now be rejected.
        User user = userRepository.findByEmail("locked-refresh@test.com").orElseThrow();
        user.setStatus(UserStatus.LOCKED);
        userRepository.save(user);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(login.refreshToken())))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void me_withToken_200_seesPermissions() throws Exception {
        // EMPLOYEE user has product:create (seeded above).
        Role employeeRole = roleRepository.findByCodeIgnoreCase("EMPLOYEE").orElseThrow();
        userRepository.save(User.builder()
                .email("me-employee@test.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(employeeRole)
                .status(UserStatus.ACTIVE)
                .build());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("me-employee@test.com", "password123"))))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").exists())
                .andExpect(jsonPath("$.data.email").value("me-employee@test.com"))
                .andExpect(jsonPath("$.data.roleCode").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.permissions", hasItem("product:create")));
    }

    @Test
    void me_customer_returnsProfileName() throws Exception {
        authService.register(registerRequest("me-customer@test.com"));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("me-customer@test.com", "password123"))))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Test User"));
    }

    @Test
    void me_noToken_401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void me_customer_returnsPhoneAndAddress() throws Exception {
        authService.register(registerRequest("me-contact@test.com"));
        Customer customer = customerRepository.findByUser_Email("me-contact@test.com").orElseThrow();
        customer.setAddress("123 Nguyen Trai");
        customerRepository.save(customer);

        LoginResponse login = authService.login(
                new LoginRequest("me-contact@test.com", "password123"), null, null);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("1234567890"))
                .andExpect(jsonPath("$.data.address").value("123 Nguyen Trai"));
    }

    @Test
    void updateProfile_customer_persistsFields() throws Exception {
        authService.register(registerRequest("profile@test.com"));
        LoginResponse login = authService.login(
                new LoginRequest("profile@test.com", "password123"), null, null);

        mockMvc.perform(patch("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\",\"phone\":\"0987654321\",\"address\":\"45 Le Loi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.phone").value("0987654321"))
                .andExpect(jsonPath("$.data.address").value("45 Le Loi"));

        Customer customer = customerRepository.findByUser_Email("profile@test.com").orElseThrow();
        assertThat(customer.getName()).isEqualTo("New Name");
        assertThat(customer.getPhone()).isEqualTo("0987654321");
        assertThat(customer.getAddress()).isEqualTo("45 Le Loi");
    }

    @Test
    void updateProfile_noToken_401() throws Exception {
        mockMvc.perform(patch("/api/v1/auth/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\",\"phone\":\"0987654321\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfile_employee_403() throws Exception {
        Role employeeRole = roleRepository.findByCodeIgnoreCase("EMPLOYEE").orElseThrow();
        userRepository.save(User.builder()
                .email("profile-employee@test.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(employeeRole)
                .status(UserStatus.ACTIVE)
                .build());

        LoginResponse login = authService.login(
                new LoginRequest("profile-employee@test.com", "password123"), null, null);

        mockMvc.perform(patch("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\",\"phone\":\"0987654321\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void changePassword_wrongCurrent_401() throws Exception {
        authService.register(registerRequest("change-wrong@test.com"));
        LoginResponse login = authService.login(
                new LoginRequest("change-wrong@test.com", "password123"), null, null);

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"not-the-password\",\"newPassword\":\"newpassword123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid password"));
    }

    @Test
    void changePassword_shortNewPassword_400() throws Exception {
        authService.register(registerRequest("change-short@test.com"));
        LoginResponse login = authService.login(
                new LoginRequest("change-short@test.com", "password123"), null, null);

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password123\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_success_revokesOtherSessions_keepsCurrent() throws Exception {
        authService.register(registerRequest("change-ok@test.com"));
        LoginResponse current = authService.login(
                new LoginRequest("change-ok@test.com", "password123"), null, null);
        LoginResponse other = authService.login(
                new LoginRequest("change-ok@test.com", "password123"), null, null);

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + current.accessToken())
                        .cookie(new Cookie(RefreshTokenCookie.NAME, current.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password123\",\"newPassword\":\"newpassword123\"}"))
                .andExpect(status().isOk());

        // Another device is logged out.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, other.refreshToken())))
                .andExpect(status().isUnauthorized());

        // The device that changed the password keeps its session.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, current.refreshToken())))
                .andExpect(status().isOk());

        // New password works, old one does not.
        assertThatCode(() -> authService.login(
                new LoginRequest("change-ok@test.com", "newpassword123"), null, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("change-ok@test.com", "password123"), null, null))
                .isInstanceOf(InvalidPasswordException.class);
    }
}
