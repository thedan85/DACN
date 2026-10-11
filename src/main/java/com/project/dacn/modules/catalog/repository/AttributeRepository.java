package com.project.dacn.modules.catalog.repository;

import com.project.dacn.modules.catalog.entity.Attribute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttributeRepository extends JpaRepository<Attribute, Long> {
    Optional<Attribute> findByAttributeId(String attributeId);

    Optional<Attribute> findByNameIgnoreCase(String name);
}
