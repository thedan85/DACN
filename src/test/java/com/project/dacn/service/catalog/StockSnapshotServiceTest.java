package com.project.dacn.service.catalog;

import com.project.dacn.dto.request.catalog.StockSnapshotRequest;
import com.project.dacn.dto.response.catalog.StockSnapshotResponse;
import com.project.dacn.repository.catalog.VariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockSnapshotServiceTest {

    @Mock
    private VariantRepository repo;

    private StockSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new StockSnapshotService(repo);
    }

    @Test
    void zeroCombinedUniqueIdsAreRejectedBeforeAnyQuery() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.snapshots(new StockSnapshotRequest(List.of(), List.of())));

        verifyNoInteractions(repo);
    }

    @Test
    void oneHundredAndOneCombinedUniqueIdsAreRejectedBeforeAnyQuery() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.snapshots(
                new StockSnapshotRequest(ids("PRD_", 51), ids("VAR_", 50))));

        verifyNoInteractions(repo);
    }

    @Test
    void invalidShapesAreRejectedBeforeAnyQuery() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.snapshots(null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.snapshots(new StockSnapshotRequest(null, List.of("VAR_1"))));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.snapshots(new StockSnapshotRequest(List.of("PRD_1"), null)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.snapshots(new StockSnapshotRequest(List.of(" "), List.of())));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.snapshots(new StockSnapshotRequest(Arrays.asList((String) null), List.of())));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.snapshots(new StockSnapshotRequest(List.of("x".repeat(256)), List.of())));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.snapshots(new StockSnapshotRequest(List.of(), List.of("x".repeat(256)))));

        verifyNoInteractions(repo);
    }

    @Test
    void duplicateIdsCountOnceAndQueryWithTheDedupedLists() {
        List<String> products = ids("PRD_", 60);
        List<String> variants = ids("VAR_", 40);
        List<String> duplicatedProducts = new ArrayList<>(products);
        duplicatedProducts.add(products.get(0));
        List<String> duplicatedVariants = new ArrayList<>(variants);
        duplicatedVariants.add(variants.get(0));
        when(repo.findSnapshotStockByProductIdIn(products)).thenReturn(List.of());
        when(repo.findStockByVariantIdIn(variants)).thenReturn(List.of());

        StockSnapshotResponse response = service.snapshots(
                new StockSnapshotRequest(duplicatedProducts, duplicatedVariants));

        assertThat(response.products()).isEmpty();
        assertThat(response.variants()).isEmpty();
        verify(repo).findSnapshotStockByProductIdIn(products);
        verify(repo).findStockByVariantIdIn(variants);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void anIdInBothArraysCountsOnceAndKeepsBothRoles() {
        when(repo.findSnapshotStockByProductIdIn(List.of("shared", "PRD_2")))
                .thenReturn(List.of(productStock("shared", 5L)));
        when(repo.findStockByVariantIdIn(List.of("shared")))
                .thenReturn(List.of(variantStock("shared", "shared", 5, 3L)));

        StockSnapshotResponse response = service.snapshots(
                new StockSnapshotRequest(List.of("shared", "PRD_2"), List.of("shared")));

        assertThat(response.products()).singleElement().satisfies(row -> {
            assertThat(row.productId()).isEqualTo("shared");
            assertThat(row.stock()).isEqualTo(5L);
        });
        assertThat(response.variants()).singleElement().satisfies(row -> {
            assertThat(row.variantId()).isEqualTo("shared");
            assertThat(row.productId()).isEqualTo("shared");
            assertThat(row.stock()).isEqualTo(5);
            assertThat(row.stockRevision()).isEqualTo(3L);
        });
    }

    @Test
    void onlyRowsReturnedByTheVisibilityQueriesAreMapped() {
        when(repo.findSnapshotStockByProductIdIn(List.of("visible", "draft", "unknown")))
                .thenReturn(List.of(productStock("visible", 8L)));
        when(repo.findStockByVariantIdIn(List.of("visibleVariant", "deletedVariant", "unknownVariant")))
                .thenReturn(List.of(variantStock("visibleVariant", "visible", 8, 4L)));

        StockSnapshotResponse response = service.snapshots(new StockSnapshotRequest(
                List.of("visible", "draft", "unknown"),
                List.of("visibleVariant", "deletedVariant", "unknownVariant")));

        assertThat(response.products()).singleElement()
                .extracting(StockSnapshotResponse.ProductStock::productId)
                .isEqualTo("visible");
        assertThat(response.variants()).singleElement()
                .extracting(StockSnapshotResponse.VariantStock::variantId)
                .isEqualTo("visibleVariant");
    }

    @Test
    void productOnlyRequestIssuesOnlyTheAggregateQuery() {
        when(repo.findSnapshotStockByProductIdIn(List.of("PRD_1")))
                .thenReturn(List.of(productStock("PRD_1", 0L)));

        StockSnapshotResponse response = service.snapshots(
                new StockSnapshotRequest(List.of("PRD_1"), List.of()));

        assertThat(response.products()).singleElement()
                .satisfies(row -> assertThat(row.stock()).isZero());
        assertThat(response.variants()).isEmpty();
        verify(repo).findSnapshotStockByProductIdIn(List.of("PRD_1"));
        verify(repo, never()).findStockByVariantIdIn(any());
        verifyNoMoreInteractions(repo);
    }

    @Test
    void variantOnlyRequestIssuesOnlyTheVariantBatchQuery() {
        when(repo.findStockByVariantIdIn(List.of("VAR_1")))
                .thenReturn(List.of(variantStock("VAR_1", "PRD_1", 2, 1L)));

        StockSnapshotResponse response = service.snapshots(
                new StockSnapshotRequest(List.of(), List.of("VAR_1")));

        assertThat(response.variants()).singleElement()
                .satisfies(row -> assertThat(row.stockRevision()).isEqualTo(1L));
        assertThat(response.products()).isEmpty();
        verify(repo).findStockByVariantIdIn(List.of("VAR_1"));
        verify(repo, never()).findSnapshotStockByProductIdIn(any());
        verifyNoMoreInteractions(repo);
    }

    private static List<String> ids(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(index -> prefix + index).toList();
    }

    private static VariantRepository.ProductStockView productStock(String productId, Long stock) {
        return new VariantRepository.ProductStockView() {
            @Override
            public String getProductId() {
                return productId;
            }

            @Override
            public Long getTotalStock() {
                return stock;
            }
        };
    }

    private static VariantRepository.VariantStockView variantStock(String variantId, String productId,
                                                                   Integer stock, long revision) {
        return new VariantRepository.VariantStockView() {
            @Override
            public String getVariantId() {
                return variantId;
            }

            @Override
            public String getProductId() {
                return productId;
            }

            @Override
            public Integer getStock() {
                return stock;
            }

            @Override
            public long getStockRevision() {
                return revision;
            }
        };
    }
}
