package com.project.dacn.service.auth;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.common.exception.AccountNotFoundException;
import com.project.dacn.common.exception.EmailAlreadyExistsException;
import com.project.dacn.common.exception.GoogleLoginNotAllowedException;
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
import com.project.dacn.entity.auth.RefreshToken;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.auth.RefreshTokenRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.security.AuthenticatedUser;
import com.project.dacn.security.GoogleTokenVerifier;
import com.project.dacn.security.JwtService;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    // Two tabs can race on one rotating refresh token; short replays return
    // the already-rotated token instead of killing every session.
    private static final Duration REFRESH_REPLAY_GRACE = Duration.ofSeconds(30);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CustomerRepository customerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginResponse register(RegisterRequest request) {
        return register(request, null, null);
    }

    /**
     * Creates the account and its session in one request, so sign-up does not
     * pay for a second login round trip on the client.
     */
    public LoginResponse register(RegisterRequest request, String userAgent, String ip) {
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);

        try {
            String passwordHash = passwordEncoder.encode(request.password());
            Role customerRole = roleRepository.findByCodeIgnoreCase("CUSTOMER")
                    .orElseThrow(() -> new IllegalStateException("CUSTOMER role not found"));

            // Set last login at insert time; a fresh account needs no later UPDATE.
            User user = userRepository.save(User.builder()
                    .email(normalizedEmail)
                    .passwordHash(passwordHash)
                    .role(customerRole)
                    .lastLogin(Instant.now())
                    .build());

            customerRepository.save(Customer.builder()
                    .user(user)
                    .name(request.name())
                    .phone(request.phone())
                    .build());

            return buildSession(user, user.getUserId(), normalizedEmail,
                    toMeResponse(user), userAgent, ip);
        } catch (DataIntegrityViolationException ex) {
            // Email is unique; the IDENTITY insert fails here, not at commit.
            throw new EmailAlreadyExistsException("email already exists");
        }
    }

    public LoginResponse login(LoginRequest request, String userAgent, String ip) {
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);

        // One joined row set answers credentials, status and profile. A separate
        // findByEmail plus principal lookup used to cost two remote round trips.
        List<Object[]> rows = userRepository.findPrincipalRowsByEmail(normalizedEmail);
        if (rows.isEmpty()) {
            throw new AccountNotFoundException();
        }
        Object[] row = rows.get(0);
        String passwordHash = (String) row[2];

        if (passwordHash == null || !passwordEncoder.matches(request.password(), passwordHash)) {
            throw new InvalidPasswordException();
        }
        ensureLoginAllowed((UserStatus) row[3]);

        String userId = (String) row[0];
        String email = (String) row[1];
        userRepository.updateLastLogin(userId, Instant.now());

        return buildSession(
                userRepository.getReferenceById(((Number) row[11]).longValue()),
                userId, email, toMeResponse(rows), userAgent, ip);
    }

    private LoginResponse issueSession(User user, String userAgent, String ip) {
        user.setLastLogin(Instant.now());
        userRepository.save(user);

        return buildSession(user, user.getUserId(), user.getEmail(),
                toMeResponse(user), userAgent, ip);
    }

    private LoginResponse buildSession(User userRef, String userId, String email,
                                       MeResponse me, String userAgent, String ip) {
        String tokenId = UUID.randomUUID().toString();
        String accessToken = jwtService.generateAccessToken(userId, email);
        String refreshToken = jwtService.generateRefreshToken(userId, tokenId);

        RefreshToken token = RefreshToken.builder()
                .tokenId(tokenId)
                .user(userRef)
                .expiresAt(Instant.now().plus(
                        jwtService.getRefreshTokenExpirationMs(), ChronoUnit.MILLIS))
                .userAgent(userAgent)
                .ip(ip)
                .revoked(false)
                .build();
        refreshTokenRepository.save(token);

        return new LoginResponse(accessToken, refreshToken,
                jwtService.getAccessTokenExpirationSeconds(), "Bearer", me);
    }

    private void ensureLoginAllowed(User user) {
        ensureLoginAllowed(user.getStatus());
    }

    private void ensureLoginAllowed(UserStatus status) {
        if (status == UserStatus.LOCKED) {
            throw new UserLockedException();
        }
        if (status == UserStatus.DELETED) {
            throw new AccountNotFoundException();
        }
    }

    public LoginResponse loginWithVerifiedGoogleEmail(
            GoogleTokenVerifier.GoogleIdentity identity, String userAgent, String ip) {
        String normalizedEmail = identity.email().trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByGoogleSubIncludingDeleted(identity.sub()).orElse(null);

        if (user != null) {
            ensureGoogleCustomer(user);
            user = syncGoogleEmail(user, normalizedEmail);
        } else {
            user = userRepository.findByEmailIncludingDeleted(normalizedEmail).orElse(null);
            if (user == null) {
                user = createGoogleCustomer(identity, normalizedEmail);
            } else {
                ensureGoogleCustomer(user);
                if (user.getGoogleSub() != null) {
                    throw new GoogleLoginNotAllowedException(
                            "This email is linked to a different Google account");
                }
                // Google verified this email, so linking it to an existing
                // password account is safe and keeps the owner from being locked out.
                user.setGoogleSub(identity.sub());
                user = userRepository.saveAndFlush(user);
            }
        }

        return issueSession(user, userAgent, ip);
    }

    private void ensureGoogleCustomer(User user) {
        if (!user.getRole().getCode().equalsIgnoreCase("CUSTOMER")) {
            throw new GoogleLoginNotAllowedException(
                    "Google sign-in is only available for customer accounts; use email and password");
        }
        ensureLoginAllowed(user);
    }

    private User syncGoogleEmail(User user, String normalizedEmail) {
        if (user.getEmail().equalsIgnoreCase(normalizedEmail)) {
            return user;
        }
        user.setEmail(normalizedEmail);
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new GoogleLoginNotAllowedException(
                    "This Google email is already used by another account; sign in with that account");
        }
    }

    private User createGoogleCustomer(
            GoogleTokenVerifier.GoogleIdentity identity, String normalizedEmail) {
        Role customerRole = roleRepository.findByCodeIgnoreCase("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role not found"));

        User user;
        try {
            user = userRepository.saveAndFlush(User.builder()
                    .email(normalizedEmail)
                    .googleSub(identity.sub())
                    .role(customerRole)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // Two first-time Google logins raced; the controller retries once.
            throw new EmailAlreadyExistsException("email already exists");
        }

        int at = identity.email().indexOf('@');
        String name = at > 0 ? identity.email().substring(0, at) : identity.email();

        customerRepository.save(Customer.builder()
                .user(user)
                .name(name)
                .build());
        return user;
    }

    // Commit the revocation of a replayed token's sessions even though the
    // request still fails with InvalidTokenException.
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public RefreshResponse refresh(RefreshRequest request) {
        // Extract jti from refresh token, wrap in try-catch for invalid tokens
        String jti;
        try {
            jti = jwtService.extractJti(request.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }

        // Serialize rotations of the same token. The second tab then sees the
        // first rotation and can reuse it instead of tripping the replay alarm.
        RefreshToken token = refreshTokenRepository.findByTokenIdForUpdate(jti)
                .orElseThrow(InvalidTokenException::new);

        if (token.getRevoked()) {
            RefreshToken replacement = graceReplacement(token);
            if (replacement != null) {
                return tokensFor(replacement);
            }
            // A replayed token outside the grace window means it was stolen:
            // drop every session of that user.
            log.warn("Refresh token reuse for user {}; revoking all sessions",
                    token.getUser().getUserId());
            refreshTokenRepository.findByUser_UserId(token.getUser().getUserId())
                    .forEach(t -> t.setRevoked(true));
            throw new InvalidTokenException();
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException();
        }

        // Validate user status is ACTIVE
        User user = token.getUser();
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidTokenException();
        }

        String newTokenId = UUID.randomUUID().toString();
        token.setRevoked(true);
        token.setReplacedByTokenId(newTokenId);
        refreshTokenRepository.save(token);

        RefreshToken newToken = RefreshToken.builder()
                .tokenId(newTokenId)
                .user(user)
                .expiresAt(Instant.now().plus(jwtService.getRefreshTokenExpirationMs(), ChronoUnit.MILLIS))
                .userAgent(token.getUserAgent())
                .ip(token.getIp())
                .revoked(false)
                .build();
        refreshTokenRepository.save(newToken);

        return tokensFor(newToken);
    }

    /**
     * A just-rotated token replayed by a concurrent tab returns the replacement
     * that is already stored, so the shared cookie converges on one token.
     */
    private RefreshToken graceReplacement(RefreshToken revoked) {
        if (revoked.getReplacedByTokenId() == null) {
            return null;
        }
        RefreshToken replacement = refreshTokenRepository
                .findByTokenId(revoked.getReplacedByTokenId()).orElse(null);
        if (replacement == null
                || replacement.getRevoked()
                || replacement.getExpiresAt().isBefore(Instant.now())
                || replacement.getUser().getStatus() != UserStatus.ACTIVE
                || replacement.getCreatedAt() == null
                || replacement.getCreatedAt().isBefore(
                        Instant.now().minus(REFRESH_REPLAY_GRACE))) {
            return null;
        }
        return replacement;
    }

    private RefreshResponse tokensFor(RefreshToken token) {
        User user = token.getUser();
        return new RefreshResponse(
                jwtService.generateAccessToken(user.getUserId(), user.getEmail()),
                jwtService.generateRefreshToken(user.getUserId(), token.getTokenId(), token.getExpiresAt()),
                jwtService.getAccessTokenExpirationSeconds(),
                "Bearer",
                toMeResponse(user));
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

        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser principal) {
            return toMeResponse(principal);
        }

        User user = userRepository.findByUserId(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("user not found"));

        return toMeResponse(user);
    }

    /**
     * Builds the profile from the same single joined row set the JWT filter
     * uses, so a login/refresh costs one query instead of role, permission and
     * customer lookups.
     */
    private MeResponse toMeResponse(User user) {
        return toMeResponse(userRepository.findPrincipalRowsByUserId(user.getUserId()));
    }

    private MeResponse toMeResponse(List<Object[]> rows) {
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("user not found");
        }

        Object[] row = rows.get(0);
        String email = (String) row[1];
        Long customerId = (Long) row[7];
        List<String> permissions = rows.stream()
                .map(permissionRow -> (String) permissionRow[6])
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        return new MeResponse(
                (String) row[0],
                email,
                customerId != null ? (String) row[8] : email,
                (String) row[9],
                (String) row[10],
                (String) row[4],
                (String) row[5],
                permissions,
                row[2] != null
        );
    }

    private MeResponse toMeResponse(AuthenticatedUser principal) {
        return new MeResponse(
                principal.userId(),
                principal.email(),
                principal.name(),
                principal.phone(),
                principal.address(),
                principal.roleCode(),
                principal.roleName(),
                principal.permissions(),
                principal.hasPassword());
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

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser principal) {
            return new MeResponse(
                    principal.userId(),
                    principal.email(),
                    customer.getName(),
                    customer.getPhone(),
                    customer.getAddress(),
                    principal.roleCode(),
                    principal.roleName(),
                    principal.permissions(),
                    principal.hasPassword());
        }
        return me();
    }

    public void changePassword(ChangePasswordRequest request, String currentRefreshToken) {
        User user = currentUser();

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
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
        return userRepository.findByUserId(auth.getName())
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

}
