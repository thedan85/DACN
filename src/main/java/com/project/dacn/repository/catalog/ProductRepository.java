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
}
