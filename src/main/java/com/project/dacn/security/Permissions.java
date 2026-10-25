package com.project.dacn.security;

import org.springframework.security.core.Authentication;

/**
 * Permission codes, referenced by both the URL rules in SecurityConfig and the
 * {@code @PreAuthorize} annotations so the two layers cannot drift.
 */
public final class Permissions {

    public static final String PRODUCT_CREATE = "product:create";
    public static final String PRODUCT_UPDATE = "product:update";
    public static final String PRODUCT_DELETE = "product:delete";
    public static final String VARIANT_CREATE = "variant:create";
    public static final String VARIANT_UPDATE = "variant:update";
    public static final String VARIANT_DELETE = "variant:delete";
    public static final String CATEGORY_CREATE = "category:create";
    public static final String CATEGORY_UPDATE = "category:update";
    public static final String CATEGORY_DELETE = "category:delete";
    public static final String ATTRIBUTE_CREATE = "attribute:create";
    public static final String ATTRIBUTE_UPDATE = "attribute:update";
    public static final String ATTRIBUTE_DELETE = "attribute:delete";
    public static final String MEDIA_CREATE = "media:create";
    public static final String MEDIA_UPDATE = "media:update";
    public static final String MEDIA_DELETE = "media:delete";
    public static final String ORDER_READ = "order:read";
    public static final String ORDER_UPDATE = "order:update";

    /**
     * Optional-privilege check: a token holding this permission may see DRAFT
     * and INACTIVE catalog rows.
     */
    public static final String VIEW_HIDDEN_PRODUCTS = PRODUCT_CREATE;

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
