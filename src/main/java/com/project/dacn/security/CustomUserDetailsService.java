package com.project.dacn.security;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.repository.auth.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        List<Object[]> rows = userRepository.findPrincipalRowsByEmail(email);
        if (rows.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + email);
        }
        return toUserDetails(rows);
    }

    /**
     * JWT subject is the immutable user business id, so a changed email does
     * not invalidate tokens that are already in flight.
     */
    @Transactional(readOnly = true)
    public UserDetails loadUserByUserId(String userId) throws UsernameNotFoundException {
        List<Object[]> rows = userRepository.findPrincipalRowsByUserId(userId);
        if (rows.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + userId);
        }
        return toUserDetails(rows);
    }

    private UserDetails toUserDetails(List<Object[]> rows) {
        Object[] row = rows.get(0);
        String email = (String) row[1];
        String passwordHash = (String) row[2];
        UserStatus status = (UserStatus) row[3];
        Long customerId = (Long) row[7];

        List<String> permissions = rows.stream()
                .map(permissionRow -> (String) permissionRow[6])
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // Spring's User rejects a null password; Google-only accounts authenticate by JWT.
        String password = passwordHash == null ? "" : passwordHash;
        return new AuthenticatedUser(
                (String) row[0],
                email,
                customerId != null ? (String) row[8] : email,
                (String) row[9],
                (String) row[10],
                (String) row[4],
                (String) row[5],
                permissions,
                passwordHash != null,
                customerId,
                password,
                status == UserStatus.ACTIVE,
                status != UserStatus.LOCKED
        );
    }
}
