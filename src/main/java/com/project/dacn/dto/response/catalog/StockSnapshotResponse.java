package com.project.dacn.dto.response.catalog;

import java.util.List;

public record StockSnapshotResponse(List<ProductStock> products, List<VariantStock> variants) {

    public record ProductStock(String productId, Long stock) {
    }

    public record VariantStock(String variantId, String productId, Integer stock, long stockRevision) {
    }
}
