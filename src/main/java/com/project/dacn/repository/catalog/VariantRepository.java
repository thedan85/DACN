package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.Variant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VariantRepository extends JpaRepository<Variant, Long> {
    Optional<Variant> findByVariantId(String variantId);

    Optional<Variant> findBySku(String sku);

    List<Variant> findByProduct_ProductId(String productId);
}
