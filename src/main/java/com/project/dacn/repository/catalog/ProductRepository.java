package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.Product;
import com.project.dacn.common.constant.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    Optional<Product> findByProductId(String productId);

    List<Product> findByStatusNot(ProductStatus status);

    Page<Product> findByStatusNot(ProductStatus status, Pageable pageable);

    /**
     * Admin/audit path bypassing {@code @SQLRestriction("product_status <> 'DELETED'")}.
     * Native queries are not augmented with the entity restriction, so explicit
     * {@code DELETED} listing works here while default JPA queries stay hidden.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT * FROM products p
            WHERE p.product_status = :status
            AND (:name IS NULL OR LOWER(p.product_name) LIKE LOWER(CONCAT('%', :name, '%')))
            AND (:categoryId IS NULL OR p.category_id = :categoryId)
            """, countQuery = """
            SELECT COUNT(*) FROM products p
            WHERE p.product_status = :status
            AND (:name IS NULL OR LOWER(p.product_name) LIKE LOWER(CONCAT('%', :name, '%')))
            AND (:categoryId IS NULL OR p.category_id = :categoryId)
            """, nativeQuery = true)
    Page<Product> findByStatusIncludingDeleted(
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("name") String name,
            @org.springframework.data.repository.query.Param("categoryId") String categoryId,
            Pageable pageable);
}
