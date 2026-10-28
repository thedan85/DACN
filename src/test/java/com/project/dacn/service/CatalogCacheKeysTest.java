package com.project.dacn.service;

import com.project.dacn.common.constant.ProductSort;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.dto.request.catalog.ProductFilter;
import com.project.dacn.service.catalog.CatalogCacheKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogCacheKeysTest {

    private static final Pageable PAGE = PageRequest.of(0, 20);
    private static final ProductFilter ACTIVE_FILTER =
            new ProductFilter(null, ProductStatus.ACTIVE, null);

    @Test
    void generationKeyIsScopePrefixed() {
        assertThat(CatalogCacheKeys.generationKey("product")).isEqualTo("pim:product:gen");
        assertThat(CatalogCacheKeys.generationKey("variant")).isEqualTo("pim:variant:gen");
        assertThat(CatalogCacheKeys.generationKey("category")).isEqualTo("pim:category:gen");
    }

    @Test
    void productSlugKeyEmbedsGenerationAndLowercasesSlug() {
        assertThat(CatalogCacheKeys.productSlug(5, "Ember-Tetra"))
                .isEqualTo("pim:product:slug:5:ember-tetra");
        assertThat(CatalogCacheKeys.productSlug(6, "Ember-Tetra"))
                .isNotEqualTo(CatalogCacheKeys.productSlug(5, "Ember-Tetra"));
    }

    @Test
    void productDetailKeyIncludesGenerationLowercaseSlugAndReviewPage() {
        assertThat(CatalogCacheKeys.productDetail(7L, "Ember-Tetra", PageRequest.of(2, 12)))
                .isEqualTo("pim:product:detail:7:ember-tetra:2:12:none");
    }

    @Test
    void productDetailAggregateTtlIsFortyFiveSeconds() {
        assertThat(CatalogCacheKeys.PRODUCT_DETAIL_AGGREGATE_TTL)
                .isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void productSlugTtlStaysFiveMinutes() {
        assertThat(CatalogCacheKeys.PRODUCT_SLUG_TTL).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void variantByProductKeyLowercasesSlugAndOrdersSegments() {
        assertThat(CatalogCacheKeys.variantByProductSlug("Congo-Tetra", 3, PAGE))
                .isEqualTo("pim:variant:by-product:congo-tetra:3:0:20:none");
    }

    @Test
    void pageKeysUseCanonicalSort() {
        assertThat(CatalogCacheKeys.productList(2, PageRequest.of(0, 20)))
                .isEqualTo("pim:product:list:2:0:20:none");
        assertThat(CatalogCacheKeys.categoryList(2, PageRequest.of(1, 12, Sort.by("name").ascending())))
                .isEqualTo("pim:category:list:2:1:12:name:ASC");
        assertThat(CatalogCacheKeys.variantList(2, PageRequest.of(0, 20)))
                .isEqualTo("pim:variant:list:2:0:20:none");
    }

    @Test
    void searchPayloadIsLengthPrefixed() {
        assertThat(CatalogCacheKeys.searchPayload(
                new ProductFilter("a|b", ProductStatus.ACTIVE, null,
                        null, null, ProductSort.NEWEST), PAGE))
                .isEqualTo("3:a|b" + "6:ACTIVE" + "0:" + "0:" + "0:" + "6:NEWEST" + "1:0" + "2:20");
    }

    @Test
    void searchKeyNormalizesKeywordAndPriceFormat() {
        String lower = CatalogCacheKeys.productSearch(1,
                new ProductFilter("  Ember  ", ProductStatus.ACTIVE, " fish ",
                        new BigDecimal("179000.00"), new BigDecimal("1.990000E+5"), ProductSort.PRICE_ASC),
                PAGE);
        String canonical = CatalogCacheKeys.productSearch(1,
                new ProductFilter("ember", ProductStatus.ACTIVE, "fish",
                        new BigDecimal("179000"), new BigDecimal("199000"), ProductSort.PRICE_ASC),
                PAGE);
        assertThat(lower).isEqualTo(canonical);
        assertThat(lower).startsWith("pim:product:search:1:").hasSize("pim:product:search:1:".length() + 64);
    }

    @Test
    void searchKeyIgnoresPageableSortButNotPageOrSizeOrFilter() {
        Pageable withSort = PageRequest.of(0, 20, Sort.by("name"));
        assertThat(CatalogCacheKeys.productSearch(1, ACTIVE_FILTER, withSort))
                .isEqualTo(CatalogCacheKeys.productSearch(1, ACTIVE_FILTER, PAGE));
        assertThat(CatalogCacheKeys.productSearch(1, ACTIVE_FILTER, PageRequest.of(1, 20)))
                .isNotEqualTo(CatalogCacheKeys.productSearch(1, ACTIVE_FILTER, PAGE));
        assertThat(CatalogCacheKeys.productSearch(1,
                new ProductFilter("a", ProductStatus.ACTIVE, null), PAGE))
                .isNotEqualTo(CatalogCacheKeys.productSearch(1, ACTIVE_FILTER, PAGE));
        assertThat(CatalogCacheKeys.productSearch(1,
                new ProductFilter("a|b", ProductStatus.ACTIVE, null), PAGE))
                .isNotEqualTo(CatalogCacheKeys.productSearch(1,
                        new ProductFilter("a", ProductStatus.ACTIVE, "b"), PAGE));
    }

    @Test
    void pageCacheabilityEnforcesBoundsAndSizeWhitelist() {
        assertThat(CatalogCacheKeys.isCacheablePage(PageRequest.of(0, 12))).isTrue();
        assertThat(CatalogCacheKeys.isCacheablePage(PageRequest.of(4, 100))).isTrue();
        assertThat(CatalogCacheKeys.isCacheablePage(PageRequest.of(5, 20))).isFalse();
        assertThat(CatalogCacheKeys.isCacheablePage(PageRequest.of(0, 13))).isFalse();
        assertThat(CatalogCacheKeys.isCacheablePage(Pageable.unpaged())).isFalse();
    }

    @Test
    void searchCacheabilityRequiresActiveStatusAndShortKeyword() {
        assertThat(CatalogCacheKeys.isCacheableSearch(ACTIVE_FILTER, PAGE)).isTrue();
        assertThat(CatalogCacheKeys.isCacheableSearch(
                new ProductFilter("x".repeat(64), ProductStatus.ACTIVE, null), PAGE)).isTrue();
        assertThat(CatalogCacheKeys.isCacheableSearch(
                new ProductFilter("x".repeat(65), ProductStatus.ACTIVE, null), PAGE)).isFalse();
        assertThat(CatalogCacheKeys.isCacheableSearch(
                new ProductFilter(null, ProductStatus.DRAFT, null), PAGE)).isFalse();
        assertThat(CatalogCacheKeys.isCacheableSearch(
                new ProductFilter(null, null, null), PAGE)).isFalse();
        assertThat(CatalogCacheKeys.isCacheableSearch(ACTIVE_FILTER, PageRequest.of(5, 20))).isFalse();
    }
}
