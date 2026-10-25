package com.project.dacn.security;

import org.springframework.security.core.Authentication;

/**
 * Optional-privilege check for endpoints that are public but expose more data
 * to catalog managers (e.g. listing DRAFT products) when a token is present.
 */
public final class Permissions {

    public static final String VIEW_HIDDEN_PRODUCTS = "product:create";

    private Permissions() {
    }

    public static boolean has(Authentication authentication, String permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }
}
