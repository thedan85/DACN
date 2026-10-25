package com.project.dacn.repository.catalog;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.entity.catalog.Variant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VariantRepository extends JpaRepository<Variant, Long> {
    Optional<Variant> findByVariantId(String variantId);

    Optional<Variant> findBySku(String sku);

    List<Variant> findByProduct_ProductId(String productId);

    Page<Variant> findByProduct_ProductId(String productId, Pageable pageable);

    Page<Variant> findByProduct_SlugIgnoreCase(String slug, Pageable pageable);

    Page<Variant> findByDeletedFalse(Pageable pageable);

    Page<Variant> findByDeletedFalseAndProduct_Status(ProductStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"product"})
    Optional<Variant> findWithProductByVariantId(String variantId);

    @EntityGraph(attributePaths = {"product"})
    Optional<Variant> findWithProductById(Long id);

    @Modifying
    @Query("update Variant v set v.stock = v.stock - :quantity where v.id = :id and v.stock >= :quantity")
    int decrementStock(@Param("id") Long id, @Param("quantity") int quantity);

    @Modifying
    @Query("update Variant v set v.stock = v.stock + :quantity where v.id = :id")
    int incrementStock(@Param("id") Long id, @Param("quantity") int quantity);
    /**
     * One query for a whole product page: min/max price and total stock per
     * product across active variants. Soft-deleted variants are excluded
     * explicitly in addition to the entity's {@code @SQLRestriction}.
     */
    @Query("""
            SELECT new com.project.dacn.repository.catalog.ProductPriceRange(
                v.product.id, MIN(v.price), MAX(v.price), SUM(v.stock))
            FROM Variant v
            WHERE v.product.id IN :productIds
              AND v.deleted = false
            GROUP BY v.product.id
            """)
    List<ProductPriceRange> findPriceRangesByProductIds(
            @Param("productIds") Collection<Long> productIds);

    interface VariantStockView {
        String getVariantId();

        Integer getStock();
    }

    interface ProductStockView {
        String getProductId();

        Long getTotalStock();
    }

    /**
     * Cache-hit stock overlay. Uses the same {@code deleted = false} filter as
     * {@link #findPriceRangesByProductIds}, so hit and miss agree.
     */
    @Query("""
            SELECT v.variantId AS variantId, v.stock AS stock
            FROM Variant v
            WHERE v.variantId = :variantId
              AND v.deleted = false
            """)
    Optional<VariantStockView> findStockByVariantId(@Param("variantId") String variantId);

    @Query("""
            SELECT v.variantId AS variantId, v.stock AS stock
            FROM Variant v
            WHERE v.variantId IN :variantIds
              AND v.deleted = false
            """)
    List<VariantStockView> findStockByVariantIdIn(@Param("variantIds") Collection<String> variantIds);

    @Query("""
            SELECT v.product.productId AS productId, SUM(v.stock) AS totalStock
            FROM Variant v
            WHERE v.product.productId = :productId
              AND v.deleted = false
            GROUP BY v.product.productId
            """)
    Optional<ProductStockView> findStockByProductId(@Param("productId") String productId);

    @Query("""
            SELECT v.product.productId AS productId, SUM(v.stock) AS totalStock
            FROM Variant v
            WHERE v.product.productId IN :productIds
              AND v.deleted = false
            GROUP BY v.product.productId
            """)
    List<ProductStockView> findStockByProductIdIn(@Param("productIds") Collection<String> productIds);
}
