package com.project.dacn.repository.catalog;

import java.math.BigDecimal;

/**
 * Aggregated variant price range for one product, loaded in a single query
 * for a whole product page to avoid N+1 lookups.
 */
public record ProductPriceRange(
        Long productId,
        BigDecimal minPrice,
        BigDecimal maxPrice) {
}
