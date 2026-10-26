package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.ProductAttributeValue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, Long> {
    @EntityGraph(attributePaths = {"attribute", "attributeValue"})
    List<ProductAttributeValue> findByProduct_ProductId(String productId);

    @EntityGraph(attributePaths = {"attribute", "attributeValue"})
    Page<ProductAttributeValue> findByProduct_ProductId(String productId, Pageable pageable);

    Optional<ProductAttributeValue> findByProductValueId(String productValueId);
}
