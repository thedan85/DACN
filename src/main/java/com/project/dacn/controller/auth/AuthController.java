package com.project.dacn.controller.auth;

import com.project.dacn.common.exception.InvalidTokenException;
import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.request.auth.LoginRequest;
import com.project.dacn.dto.request.auth.ChangePasswordRequest;
import com.project.dacn.dto.request.auth.ProfileUpdateRequest;
import com.project.dacn.dto.request.auth.RefreshRequest;
import com.project.dacn.dto.request.auth.RegisterRequest;
import com.project.dacn.dto.response.auth.LoginResponse;
import com.project.dacn.dto.response.auth.MeResponse;
import com.project.dacn.dto.response.auth.RefreshResponse;
import com.project.dacn.dto.response.auth.UserResponse;
import com.project.dacn.security.RateLimiter;
import com.project.dacn.security.RefreshTokenCookie;
import com.project.dacn.security.TrustedOriginValidator;
import com.project.dacn.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final TrustedOriginValidator trustedOriginValidator;
    private final RateLimiter rateLimiter;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.check("rl:register:" + clientIp(httpRequest), 20, Duration.ofHours(1));
        UserResponse response = authService.register(request);
        return ResponseEntity.status(201)
                .body(ApiResponse.of(201, "registered successfully", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        rateLimiter.check("rl:login:" + ip, 10, Duration.ofMinutes(1));
        // Per-account limit so many source IPs cannot brute-force one email.
        rateLimiter.check("rl:login-email:" + request.email().toLowerCase(),
                10, Duration.ofMinutes(15));
        String userAgent = httpRequest.getHeader("User-Agent");
        LoginResponse response = authService.login(request, userAgent, ip);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.create(response.refreshToken()))
                .body(ApiResponse.ok(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        trustedOriginValidator.validate(httpRequest);

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidTokenException();
        }

        rateLimiter.check("rl:refresh:" + clientIp(httpRequest), 30, Duration.ofMinutes(1));

        RefreshResponse response = authService.refresh(new RefreshRequest(refreshToken));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.create(response.refreshToken()))
                .body(ApiResponse.ok(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        trustedOriginValidator.validate(httpRequest);

        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(new RefreshRequest(refreshToken));
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear())
                .body(ApiResponse.ok(null));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me() {
        MeResponse response = authService.me();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> updateProfile(
            @Valid @RequestBody ProfileUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.updateProfile(request)));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
            Authentication authentication) {
        rateLimiter.check("rl:pwd:" + authentication.getName(), 5, Duration.ofMinutes(1));
        authService.changePassword(request, refreshToken);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private static String clientIp(HttpServletRequest request) {
        String cloudflareIp = request.getHeader("CF-Connecting-IP");
        if (cloudflareIp != null && !cloudflareIp.isBlank()) {
            String trimmed = cloudflareIp.trim();
            if (trimmed.length() <= 45 && trimmed.indexOf(',') < 0) {
                return trimmed;
            }
        }
        // getRemoteAddr() is rewritten from the leftmost X-Forwarded-For, so it is
        // forgeable here; all direct traffic shares one bucket instead.
        return "local";
    }
}
