package com.project.dacn.service.auth;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.common.exception.AccountNotFoundException;
import com.project.dacn.common.exception.EmailAlreadyExistsException;
import com.project.dacn.common.exception.InvalidPasswordException;
import com.project.dacn.common.exception.InvalidTokenException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.exception.UserLockedException;
import com.project.dacn.dto.request.auth.LoginRequest;
import com.project.dacn.dto.request.auth.RefreshRequest;
import com.project.dacn.dto.request.auth.RegisterRequest;
import com.project.dacn.dto.request.auth.ChangePasswordRequest;
import com.project.dacn.dto.request.auth.ProfileUpdateRequest;
import com.project.dacn.dto.response.auth.LoginResponse;
import com.project.dacn.dto.response.auth.MeResponse;
import com.project.dacn.dto.response.auth.RefreshResponse;
import com.project.dacn.dto.response.auth.UserResponse;
import com.project.dacn.entity.auth.RefreshToken;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.mapper.auth.UserMapper;
import com.project.dacn.repository.auth.RefreshTokenRepository;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.security.JwtService;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CustomerRepository customerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserResponse register(RegisterRequest request) {
        // Normalize email to lowercase
        String normalizedEmail = request.email().toLowerCase();

        // Check if email already exists
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException("email already exists");
        }

        try {
            // Hash password
            String passwordHash = passwordEncoder.encode(request.password());

            // Get CUSTOMER role
            Role customerRole = roleRepository.findByCodeIgnoreCase("CUSTOMER")
                    .orElseThrow(() -> new IllegalStateException("CUSTOMER role not found"));

            // Create User
            User user = User.builder()
                    .email(normalizedEmail)
                    .passwordHash(passwordHash)
                    .role(customerRole)
                    .build();
            user = userRepository.save(user);

            // Create Customer profile
            Customer customer = Customer.builder()
                    .user(user)
                    .name(request.name())
                    .phone(request.phone())
                    .build();
            customerRepository.save(customer);

            return userMapper.toResponse(user);
        } catch (DataIntegrityViolationException ex) {
            // Race condition: another thread inserted same email between check and insert
            throw new EmailAlreadyExistsException("email already exists");
        }
    }

    public LoginResponse login(LoginRequest request, String userAgent, String ip) {
        // Normalize email to lowercase
        String normalizedEmail = request.email().toLowerCase();

        // Find user
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(AccountNotFoundException::new);

        // Validate password
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidPasswordException();
        }

        // Check user status
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new UserLockedException();
        }
        if (user.getStatus() == UserStatus.DELETED) {
            throw new AccountNotFoundException();
        }

        // Update last login
        user.setLastLogin(Instant.now());
        userRepository.save(user);

        // Generate tokens
        String tokenId = UUID.randomUUID().toString();
        String accessToken = jwtService.generateAccessToken(user.getUserId(), user.getEmail());
        String refreshToken = jwtService.generateRefreshToken(user.getUserId(), tokenId);

        // Hash refresh token and save
        String tokenHash = hashToken(refreshToken);

        RefreshToken token = RefreshToken.builder()
                .tokenId(tokenId)
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plus(jwtService.getRefreshTokenExpirationMs(), ChronoUnit.MILLIS))
                .userAgent(userAgent)
                .ip(ip)
                .revoked(false)
                .build();
        refreshTokenRepository.save(token);

        return new LoginResponse(
                accessToken,
                refreshToken,
                jwtService.getAccessTokenExpirationSeconds(),
                "Bearer"
        );
    }

    public RefreshResponse refresh(RefreshRequest request) {
        // Extract jti from refresh token, wrap in try-catch for invalid tokens
        String jti;
        try {
            jti = jwtService.extractJti(request.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }

        // Find token by jti
        RefreshToken token = refreshTokenRepository.findByTokenId(jti)
                .orElseThrow(InvalidTokenException::new);

        // Validate token: not revoked, not expired
        if (token.getRevoked() || token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException();
        }

        // Validate user status is ACTIVE
        User user = token.getUser();
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidTokenException();
        }

        // Revoke old token
        token.setRevoked(true);
        refreshTokenRepository.save(token);

        // Generate new tokens
        String newTokenId = UUID.randomUUID().toString();
        String newAccessToken = jwtService.generateAccessToken(user.getUserId(), user.getEmail());
        String newRefreshToken = jwtService.generateRefreshToken(user.getUserId(), newTokenId);

        // Hash and save new refresh token
        String tokenHash = hashToken(newRefreshToken);

        RefreshToken newToken = RefreshToken.builder()
                .tokenId(newTokenId)
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plus(jwtService.getRefreshTokenExpirationMs(), ChronoUnit.MILLIS))
                .userAgent(token.getUserAgent())
                .ip(token.getIp())
                .revoked(false)
                .build();
        refreshTokenRepository.save(newToken);

        return new RefreshResponse(
                newAccessToken,
                newRefreshToken,
                jwtService.getAccessTokenExpirationSeconds(),
                "Bearer"
        );
    }

    public void logout(RefreshRequest request) {
        // Idempotent: always succeed, even if token invalid/not found
        try {
            String jti = jwtService.extractJti(request.refreshToken());
            refreshTokenRepository.findByTokenId(jti)
                    .ifPresent(token -> {
                        token.setRevoked(true);
                        refreshTokenRepository.save(token);
                    });
        } catch (JwtException | IllegalArgumentException e) {
            // Token invalid, ignore - logout is idempotent
        }
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("user not found"));

        List<RolePermission> rolePermissions = rolePermissionRepository
                .findByRole_RoleIdWithPermission(user.getRole().getRoleId());

        List<String> permissions = rolePermissions.stream()
                .map(rp -> rp.getPermission().getCode())
                .toList();

        Customer customer = customerRepository.findByUser_UserId(user.getUserId()).orElse(null);

        String name = customer != null ? customer.getName() : user.getEmail();
        String phone = customer != null ? customer.getPhone() : null;
        String address = customer != null ? customer.getAddress() : null;

        return new MeResponse(
                user.getUserId(),
                user.getEmail(),
                name,
                phone,
                address,
                user.getRole().getCode(),
                user.getRole().getName(),
                permissions
        );
    }

    public MeResponse updateProfile(ProfileUpdateRequest request) {
        User user = currentUser();
        Customer customer = customerRepository.findByUser_UserId(user.getUserId())
                .orElseThrow(() -> new AccessDeniedException("customer profile not found"));

        customer.setName(request.name().trim());
        customer.setPhone(request.phone().trim());
        customer.setAddress(
                request.address() == null || request.address().isBlank()
                        ? null
                        : request.address().trim());
        customerRepository.save(customer);

        return me();
    }

    public void changePassword(ChangePasswordRequest request, String currentRefreshToken) {
        User user = currentUser();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidPasswordException();
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Keep the session that performed the change; drop every other device.
        // Deleting (not revoking) avoids the replay detector revoking the
        // surviving session when an old device retries its refresh token.
        String keepTokenId = currentRefreshTokenId(currentRefreshToken);
        List<RefreshToken> tokens = refreshTokenRepository.findByUser_UserId(user.getUserId());
        List<RefreshToken> otherTokens = tokens.stream()
                .filter(token -> !token.getTokenId().equals(keepTokenId))
                .toList();
        refreshTokenRepository.deleteAll(otherTokens);
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("user not found"));
    }

    private String currentRefreshTokenId(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        try {
            // Refresh tokens carry a jti claim; access tokens do not.
            String tokenId = jwtService.extractJti(refreshToken);
            return tokenId == null || tokenId.isBlank() ? null : tokenId;
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
