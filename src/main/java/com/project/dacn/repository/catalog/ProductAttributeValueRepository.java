package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.ProductAttributeValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, Long> {
    List<ProductAttributeValue> findByProduct_ProductId(String productId);
}
