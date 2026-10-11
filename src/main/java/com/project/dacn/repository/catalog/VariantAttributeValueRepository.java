package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.VariantAttributeValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VariantAttributeValueRepository extends JpaRepository<VariantAttributeValue, Long> {
    List<VariantAttributeValue> findByVariant_VariantId(String variantId);
}
