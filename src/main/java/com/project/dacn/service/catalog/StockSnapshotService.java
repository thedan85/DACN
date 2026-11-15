package com.project.dacn.service.catalog;

import com.project.dacn.dto.request.catalog.StockSnapshotRequest;
import com.project.dacn.dto.response.catalog.StockSnapshotResponse;
import com.project.dacn.dto.response.catalog.StockSnapshotResponse.ProductStock;
import com.project.dacn.dto.response.catalog.StockSnapshotResponse.VariantStock;
import com.project.dacn.repository.catalog.VariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockSnapshotService {

    private static final int MAX_COMBINED_UNIQUE_IDS = 100;
    private static final int MAX_ID_LENGTH = 255;

    private final VariantRepository variantRepository;

    public StockSnapshotResponse snapshots(StockSnapshotRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        List<String> productIds = normalize(request.productIds(), "productIds");
        List<String> variantIds = normalize(request.variantIds(), "variantIds");
        long combinedUniqueIds = Stream.concat(productIds.stream(), variantIds.stream())
                .distinct()
                .count();
        if (combinedUniqueIds < 1 || combinedUniqueIds > MAX_COMBINED_UNIQUE_IDS) {
            throw new IllegalArgumentException(
                    "combined unique ids must be between 1 and " + MAX_COMBINED_UNIQUE_IDS);
        }

        List<ProductStock> products = productIds.isEmpty() ? List.of()
                : variantRepository.findSnapshotStockByProductIdIn(productIds).stream()
                        .map(row -> new ProductStock(row.getProductId(), row.getTotalStock()))
                        .toList();
        List<VariantStock> variants = variantIds.isEmpty() ? List.of()
                : variantRepository.findStockByVariantIdIn(variantIds).stream()
                        .map(row -> new VariantStock(row.getVariantId(), row.getProductId(),
                                row.getStock(), row.getStockRevision()))
                        .toList();
        return new StockSnapshotResponse(products, variants);
    }

    /** Deduplicates before counting and querying; an id in both roles stays in both lists. */
    private static List<String> normalize(List<String> ids, String field) {
        if (ids == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(field + " contains a blank id");
            }
            if (id.length() > MAX_ID_LENGTH) {
                throw new IllegalArgumentException(field + " contains an id longer than "
                        + MAX_ID_LENGTH + " characters");
            }
            unique.add(id);
        }
        return List.copyOf(unique);
    }
}
