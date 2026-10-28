package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * Matches the {@code uq_products_slug} index on {@code lower(product_slug)}.
     * Soft-deleted products stay hidden by the entity's SQL restriction.
     */
    @Query("SELECT p FROM Product p WHERE lower(p.slug) = lower(:slug)")
    Optional<Product> findBySlugIgnoreCase(@Param("slug") String slug);

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
