package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.ProductAttributeValue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, Long> {
    List<ProductAttributeValue> findByProduct_ProductId(String productId);

    Page<ProductAttributeValue> findByProduct_ProductId(String productId, Pageable pageable);

    Optional<ProductAttributeValue> findByProductValueId(String productValueId);
}
