package com.project.dacn.service.catalog;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.dto.request.catalog.ProductFilter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

public final class CatalogCacheKeys {

    public static final String PRODUCT_SCOPE = "product";
    public static final String VARIANT_SCOPE = "variant";
    public static final String CATEGORY_SCOPE = "category";

    public static final Duration PRODUCT_DETAIL_TTL = Duration.ofMinutes(5);
    public static final Duration PRODUCT_PAGE_TTL = Duration.ofMinutes(2);
    public static final Duration VARIANT_DETAIL_TTL = Duration.ofMinutes(5);
    public static final Duration VARIANT_PAGE_TTL = Duration.ofMinutes(2);
    public static final Duration CATEGORY_DETAIL_TTL = Duration.ofMinutes(10);
    public static final Duration CATEGORY_PAGE_TTL = Duration.ofMinutes(5);

    private static final int MAX_KEYWORD_LENGTH = 64;
    private static final int MAX_PAGE_NUMBER = 4;
    private static final Set<Integer> CACHEABLE_PAGE_SIZES = Set.of(12, 20, 24, 48, 100);

    private CatalogCacheKeys() {
    }

    public static String generationKey(String scope) {
        return "pim:" + scope + ":gen";
    }

    public static String productSlug(long generation, String slug) {
        return "pim:product:slug:" + generation + ":" + slug.toLowerCase(Locale.ROOT);
    }

    public static String productList(long generation, Pageable pageable) {
        return "pim:product:list:" + generation + ":" + pageSegment(pageable);
    }

    public static String productSearch(long generation, ProductFilter filter, Pageable pageable) {
        return "pim:product:search:" + generation + ":" + sha256Hex(searchPayload(filter, pageable));
    }

    public static String variantDetail(long generation, String variantId) {
        return "pim:variant:" + generation + ":" + variantId;
    }

    public static String variantList(long generation, Pageable pageable) {
        return "pim:variant:list:" + generation + ":" + pageSegment(pageable);
    }

    public static String variantByProductSlug(String slug, long generation, Pageable pageable) {
        return "pim:variant:by-product:" + slug.toLowerCase(Locale.ROOT) + ":"
                + generation + ":" + pageSegment(pageable);
    }

    public static String categoryDetail(long generation, String categoryId) {
        return "pim:category:" + generation + ":" + categoryId;
    }

    public static String categoryList(long generation, Pageable pageable) {
        return "pim:category:list:" + generation + ":" + pageSegment(pageable);
    }

    public static boolean isCacheablePage(Pageable pageable) {
        return pageable != null
                && pageable.isPaged()
                && pageable.getPageNumber() >= 0
                && pageable.getPageNumber() <= MAX_PAGE_NUMBER
                && CACHEABLE_PAGE_SIZES.contains(pageable.getPageSize());
    }

    public static boolean isCacheableSearch(ProductFilter filter, Pageable pageable) {
        if (filter == null || filter.status() != ProductStatus.ACTIVE || !isCacheablePage(pageable)) {
            return false;
        }
        String keyword = filter.keyword();
        return keyword == null || keyword.strip().length() <= MAX_KEYWORD_LENGTH;
    }

    public static String searchPayload(ProductFilter filter, Pageable pageable) {
        String keyword = filter.keyword() == null
                ? "" : filter.keyword().strip().toLowerCase(Locale.ROOT);
        String status = filter.status() == null ? "" : filter.status().name();
        String categoryId = filter.categoryId() == null ? "" : filter.categoryId().strip();
        String sort = filter.sort() == null ? "NONE" : filter.sort().name();
        // length-prefixed so delimiter values cannot shift field boundaries
        return field(keyword)
                + field(status)
                + field(categoryId)
                + field(canonical(filter.minPrice()))
                + field(canonical(filter.maxPrice()))
                + field(sort)
                + field(Integer.toString(pageable.getPageNumber()))
                + field(Integer.toString(pageable.getPageSize()));
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }

    private static String canonical(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String pageSegment(Pageable pageable) {
        return pageable.getPageNumber() + ":" + pageable.getPageSize() + ":" + sortSegment(pageable.getSort());
    }

    private static String sortSegment(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return "none";
        }
        StringBuilder segment = new StringBuilder();
        for (Sort.Order order : sort) {
            if (!segment.isEmpty()) {
                segment.append(',');
            }
            segment.append(order.getProperty()).append(':').append(order.getDirection().name());
        }
        return segment.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
