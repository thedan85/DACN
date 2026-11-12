package com.project.dacn.repository.catalog;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.entity.catalog.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    Optional<Product> findByProductId(String productId);

    /**
     * Listing pages map category and thumbnail for every row; join them in
     * the page query instead of paying two extra lazy round trips per page.
     */
    @Override
    @EntityGraph(attributePaths = {"category", "thumbnail"})
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    /**
     * Matches the {@code uq_products_slug} index on {@code lower(product_slug)}.
     * Soft-deleted products stay hidden by the entity's SQL restriction.
     * Category and thumbnail are fetched up front: every storefront mapper
     * reads both, and each lazy load here is a separate round trip.
     */
    @EntityGraph(attributePaths = {"category", "thumbnail"})
    @Query("SELECT p FROM Product p WHERE lower(p.slug) = lower(:slug)")
    Optional<Product> findBySlugIgnoreCase(@Param("slug") String slug);

    long countByStatus(ProductStatus status);

    /**
     * Active products whose total variant stock is at or below the threshold,
     * lowest stock first. The pageable keeps the response limited to the rows
     * the dashboard shows; {@link #countLowStockProducts} returns the full total.
     * The correlated subquery keeps products with no variants in the result
     * (their stock is zero) instead of dropping them with an inner join.
     * Soft-deleted products stay hidden by the entity's SQL restriction.
     */
    @Query("""
            select p.productId as productId,
                   p.slug as slug,
                   p.name as name,
                   t.url as thumbnail,
                   coalesce((select sum(v.stock)
                             from Variant v
                             where v.product = p and v.deleted = false), 0) as stock
            from Product p
            left join p.thumbnail t
            where p.status = :status
              and coalesce((select sum(v.stock)
                            from Variant v
                            where v.product = p and v.deleted = false), 0) <= :threshold
            order by coalesce((select sum(v.stock)
                               from Variant v
                               where v.product = p and v.deleted = false), 0) asc,
                     p.name asc
            """)
    List<LowStockProductView> findLowStockProducts(
            @Param("status") ProductStatus status,
            @Param("threshold") int threshold,
            Pageable pageable);

    @Query("""
            select count(p)
            from Product p
            where p.status = :status
              and coalesce((select sum(v.stock)
                            from Variant v
                            where v.product = p and v.deleted = false), 0) <= :threshold
            """)
    long countLowStockProducts(
            @Param("status") ProductStatus status,
            @Param("threshold") int threshold);

    interface LowStockProductView {
        String getProductId();

        String getSlug();

        String getName();

        String getThumbnail();

        Long getStock();
    }

    /**
     * Detail-page base graph in one round trip: product, category, thumbnail,
     * product attributes and variants. Attribute rows repeat for every variant
     * (at most 8 x 3 here), and the service dedupes them by business id.
     * Deleted products stay hidden by the entity's SQL restriction; deleted
     * variants are filtered explicitly because the join is an entity join.
     */
    @Query("""
            select new com.project.dacn.repository.catalog.ProductDetailBaseRow(
                p.productId, p.slug, p.name, p.description, p.detailDescription, p.status,
                c.categoryId, c.name, t.url, t.mediaId, p.createdAt, p.updatedAt,
                pav.productValueId, pa.attributeId, pa.name,
                av.attributeValueId, av.value, pav.createdAt,
                v.variantId, v.sku, v.price, v.stock)
            from Product p
            left join p.category c
            left join p.thumbnail t
            left join ProductAttributeValue pav on pav.product = p
            left join pav.attribute pa
            left join pav.attributeValue av
            left join Variant v on v.product = p and v.deleted = false
            where lower(p.slug) = lower(:slug)
            order by pav.id, v.id
            """)
    List<ProductDetailBaseRow> findDetailBaseBySlug(@Param("slug") String slug);

    /**
     * Uniqueness checks must see soft-deleted rows too: the DB unique index
     * covers every row, so a deleted product keeps reserving its slug.
     */
    @Query(value = """
            SELECT count(*) FROM products WHERE lower(product_slug) = lower(:slug)
            """, nativeQuery = true)
    long countBySlugIncludingDeleted(@Param("slug") String slug);

    @Query(value = """
            SELECT count(*) FROM products
            WHERE lower(product_slug) = lower(:slug) AND id <> :id
            """, nativeQuery = true)
    long countBySlugIncludingDeletedAndIdNot(
            @Param("slug") String slug, @Param("id") Long id);

    /**
     * Admin/audit path bypassing {@code @SQLRestriction("product_status <> 'DELETED'")}.
     * Native queries are not augmented with the entity restriction, so explicit
     * {@code DELETED} listing works here while default JPA queries stay hidden.
     */
    @Query(value = """
            SELECT * FROM products p
            WHERE p.product_status = :status
            AND LOWER(p.product_name) LIKE LOWER(CONCAT('%', :name, '%'))
            """, countQuery = """
            SELECT COUNT(*) FROM products p
            WHERE p.product_status = :status
            AND LOWER(p.product_name) LIKE LOWER(CONCAT('%', :name, '%'))
            """, nativeQuery = true)
    Page<Product> findByStatusIncludingDeleted(
            @Param("status") String status,
            @Param("name") String name,
            Pageable pageable);

    /**
     * Category-filtered variant of {@link #findByStatusIncludingDeleted}.
     * {@code categoryIds} is the requested category plus its descendants and
     * must not be empty: an empty native {@code IN} list is invalid SQL.
     */
    @Query(value = """
            SELECT p.* FROM products p
            JOIN categories c ON c.id = p.category_id
            WHERE p.product_status = :status
            AND LOWER(p.product_name) LIKE LOWER(CONCAT('%', :name, '%'))
            AND c.category_id IN (:categoryIds)
            """, countQuery = """
            SELECT COUNT(*) FROM products p
            JOIN categories c ON c.id = p.category_id
            WHERE p.product_status = :status
            AND LOWER(p.product_name) LIKE LOWER(CONCAT('%', :name, '%'))
            AND c.category_id IN (:categoryIds)
            """, nativeQuery = true)
    Page<Product> findByStatusIncludingDeletedInCategories(
            @Param("status") String status,
            @Param("name") String name,
            @Param("categoryIds") Collection<String> categoryIds,
            Pageable pageable);
}
