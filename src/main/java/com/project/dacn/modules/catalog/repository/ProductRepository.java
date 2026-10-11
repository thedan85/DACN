package com.project.dacn.modules.catalog.repository;

import com.project.dacn.modules.catalog.entity.Product;
import com.project.dacn.modules.common.enums.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByProductId(String productId);

    List<Product> findByStatusNot(ProductStatus status);
}
