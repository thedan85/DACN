package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.Attribute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttributeRepository extends JpaRepository<Attribute, Long> {
    Optional<Attribute> findByAttributeId(String attributeId);

    Optional<Attribute> findByNameIgnoreCase(String name);

    org.springframework.data.domain.Page<Attribute> findByDeletedFalse(org.springframework.data.domain.Pageable pageable);
}
