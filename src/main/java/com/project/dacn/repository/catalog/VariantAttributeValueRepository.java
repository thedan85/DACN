package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.VariantAttributeValue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VariantAttributeValueRepository extends JpaRepository<VariantAttributeValue, Long> {
    List<VariantAttributeValue> findByVariant_VariantId(String variantId);

    Page<VariantAttributeValue> findByVariant_VariantId(String variantId, Pageable pageable);

    Optional<VariantAttributeValue> findByVariantValueId(String variantValueId);
}
