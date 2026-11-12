package com.project.dacn.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Immutable principal built once per authenticated request. It carries the
 * profile and customer row the cart, order, and auth endpoints need, so those
 * endpoints do not re-query the same rows through {@code CustomerIdentity} or
 * {@code AuthService.me()}.
 */
public record AuthenticatedUser(
        String userId,
        String email,
        String name,
        String phone,
        String address,
        String roleCode,
        String roleName,
        List<String> permissions,
        boolean hasPassword,
        Long customerId,
        String password,
        boolean enabled,
        boolean accountNonLocked
) implements UserDetails {

    public AuthenticatedUser {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return userId;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }
}
