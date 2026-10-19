package com.project.dacn.common.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical form for product names: trimmed, single-spaced, lowercase, and
 * restricted to letters/digits/spaces. Names that normalize to the same
 * slug are rejected by {@code ProductService.assertNameAvailable}, which
 * also compares the derived {@code PR_<slug>} ID.
 */
public final class ProductNames {

    public static final int MAX_LENGTH = 200;

    private static final Pattern ALLOWED = Pattern.compile("^[\\p{L}\\p{N} ]+$");

    private ProductNames() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("product name must not be blank");
        }
        String name = raw.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "product name must be at most " + MAX_LENGTH + " characters");
        }
        if (!ALLOWED.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "product name must contain only letters, digits and spaces");
        }
        return name;
    }
}
