package com.project.dacn.modules.catalog.repository;

import com.project.dacn.modules.catalog.entity.ProductAttributeValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, Long> {
    List<ProductAttributeValue> findByProduct_ProductId(String productId);
}
