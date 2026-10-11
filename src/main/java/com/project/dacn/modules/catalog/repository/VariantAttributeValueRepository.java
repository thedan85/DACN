package com.project.dacn.modules.catalog.repository;

import com.project.dacn.modules.catalog.entity.VariantAttributeValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VariantAttributeValueRepository extends JpaRepository<VariantAttributeValue, Long> {
    List<VariantAttributeValue> findByVariant_VariantId(String variantId);
}
