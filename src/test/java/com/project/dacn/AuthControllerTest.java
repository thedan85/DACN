package com.project.dacn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.dacn.common.exception.RateLimitExceededException;
import com.project.dacn.config.SecurityConfig;
import com.project.dacn.controller.auth.AuthController;
import com.project.dacn.dto.request.auth.LoginRequest;
import com.project.dacn.dto.request.auth.RefreshRequest;
import com.project.dacn.dto.request.auth.RegisterRequest;
import com.project.dacn.dto.response.auth.LoginResponse;
import com.project.dacn.dto.response.auth.UserResponse;
import com.project.dacn.security.AuthenticationEntryPoint;
import com.project.dacn.security.CustomAccessDeniedHandler;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import com.project.dacn.security.RateLimiter;
import com.project.dacn.security.RefreshTokenCookie;
import com.project.dacn.security.TrustedOriginValidator;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.service.auth.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({
        SecurityConfig.class,
        AuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        RefreshTokenCookie.class,
        TrustedOriginValidator.class
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // NOTE: Boot 4 @WebMvcTest slice does not expose an ObjectMapper bean,
    // so instantiate locally (request DTOs contain only Strings here).
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @Test
    void register_success_201() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "test@example.com",
                "password123",
                "Test User",
                "1234567890"
        );

        UserResponse response = new UserResponse(
                "user-123",
                "test@example.com",
                null,
                "role-123",
                "Customer",
                null,
                null,
                null
        );

        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.userId").value("user-123"));
    }

    @Test
    void register_emailExists_409() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "duplicate@example.com",
                "password123",
                "Test User",
                "1234567890"
        );

        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new com.project.dacn.common.exception.EmailAlreadyExistsException("email already exists"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void login_success_200() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "password123");
        LoginResponse response = new LoginResponse(
                "access-token",
                "refresh-token",
                900,
                "Bearer"
        );

        // NOTE: userAgent/ip may be null in MockMvc (no User-Agent header),
        // so use any() instead of anyString() which does not match nulls.
        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=")));
    }

    @Test
    void login_accountNotFound_404() throws Exception {
        LoginRequest request = new LoginRequest("missing@example.com", "password123");

        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenThrow(new com.project.dacn.common.exception.AccountNotFoundException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("The account does not exist"));
    }

    @Test
    void login_invalidPassword_401() throws Exception {
        LoginRequest request = new LoginRequest("user@example.com", "wrongpass");

        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenThrow(new com.project.dacn.common.exception.InvalidPasswordException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Invalid password"));
    }

    @Test
    void login_userLocked_403() throws Exception {
        LoginRequest request = new LoginRequest("locked@example.com", "password123");

        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenThrow(new com.project.dacn.common.exception.UserLockedException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void refresh_success_200() throws Exception {
        when(authService.refresh(any(RefreshRequest.class))).thenReturn(
                new com.project.dacn.dto.response.auth.RefreshResponse(
                        "new-access-token",
                        "new-refresh-token",
                        900,
                        "Bearer"
                )
        );

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, "valid-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=")));
    }

    @Test
    void refresh_missingCookie_401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void refresh_untrustedOrigin_401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, "valid-refresh-token"))
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_success_200() throws Exception {
        doNothing().when(authService).logout(any(RefreshRequest.class));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    void login_rateLimited_429() throws Exception {
        doThrow(new RateLimitExceededException("too many requests, try again later"))
                .when(rateLimiter).check(anyString(), anyInt(), any());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@example.com", "password123"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    void refresh_rateLimited_429() throws Exception {
        doThrow(new RateLimitExceededException("too many requests, try again later"))
                .when(rateLimiter).check(anyString(), anyInt(), any());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, "valid-refresh-token")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }
}
