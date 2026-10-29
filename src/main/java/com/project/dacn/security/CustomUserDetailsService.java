package com.project.dacn.security;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final RolePermissionRepository rolePermissionRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return toUserDetails(userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email)));
    }

    /**
     * JWT subject is the immutable user business id, so a changed email does
     * not invalidate tokens that are already in flight.
     */
    @Transactional(readOnly = true)
    public UserDetails loadUserByUserId(String userId) throws UsernameNotFoundException {
        return toUserDetails(userRepository.findByUserId(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId)));
    }

    private UserDetails toUserDetails(User user) {
        List<RolePermission> rolePermissions = rolePermissionRepository
                .findByRole_RoleIdWithPermission(user.getRole().getRoleId());

        List<GrantedAuthority> authorities = rolePermissions.stream()
                .map(rp -> (GrantedAuthority) new SimpleGrantedAuthority(rp.getPermission().getCode()))
                .collect(Collectors.toList());

        boolean enabled = user.getStatus() == UserStatus.ACTIVE;
        boolean accountNonLocked = user.getStatus() != UserStatus.LOCKED;

        // Spring's User rejects a null password; Google-only accounts authenticate by JWT.
        String password = user.getPasswordHash() == null ? "" : user.getPasswordHash();
        return new org.springframework.security.core.userdetails.User(
                user.getUserId(),
                password,
                enabled,
                true, // accountNonExpired
                true, // credentialsNonExpired
                accountNonLocked,
                authorities
        );
    }
}
