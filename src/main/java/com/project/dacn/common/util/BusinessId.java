package com.project.dacn.common.util;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business key formatting. IDs are generated once on insert from data already
 * present on the entity, so {@code @PrePersist} never needs a DB round-trip.
 */
public final class BusinessId {

    private BusinessId() {
    }

    /** {@code PREFIX_<8 hex>} for entities with no unique natural key. */
    public static String random(String prefix) {
        return prefix + "_" + randomToken();
    }

    /** 8 hex chars with no prefix, for IDs assembled from other keys. */
    public static String randomToken() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** {@code PREFIX_<slug(part1)>_<slug(part2)>...} from unique natural keys. */
    public static String of(String prefix, String... parts) {
        return prefix + "_" + Arrays.stream(parts)
                .map(BusinessId::slug)
                .collect(Collectors.joining("_"));
    }

    /**
     * Like {@link #of}, but appends a stable hash of the raw parts. Distinct
     * keys that slug to the same text (john.doe@x vs john-doe@x) stay distinct.
     */
    public static String ofUnique(String prefix, String... parts) {
        String key = String.join("\u0000", parts);
        // UUIDv3 is a stable, non-secure hash used only to disambiguate slugs.
        String hash = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 12);
        return of(prefix, parts) + "-" + hash;
    }

    /** {@code PREFIX_<parentId>} — parent ID used verbatim, already normalised. */
    public static String child(String prefix, String parentId) {
        return prefix + "_" + parentId;
    }

    /** Diacritics-stripped, lowercase, dash-separated. Never returns blank. */
    public static String slug(String input) {
        if (input == null) {
            return "unnamed";
        }
        String ascii = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase()
                .replace('đ', 'd');
        String slug = ascii.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isEmpty() ? "unnamed" : slug;
    }
}
