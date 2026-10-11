package com.project.dacn.repository;

import com.project.dacn.entity.AttributeValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttributeValueRepository extends JpaRepository<AttributeValue, Long> {
    Optional<AttributeValue> findByAttributeValueId(String attributeValueId);

    List<AttributeValue> findByAttribute_AttributeId(String attributeId);
}
