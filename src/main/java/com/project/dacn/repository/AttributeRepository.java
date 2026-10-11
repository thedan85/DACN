package com.project.dacn.repository;

import com.project.dacn.entity.Attribute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttributeRepository extends JpaRepository<Attribute, Long> {
    Optional<Attribute> findByAttributeId(String attributeId);

    Optional<Attribute> findByNameIgnoreCase(String name);
}
