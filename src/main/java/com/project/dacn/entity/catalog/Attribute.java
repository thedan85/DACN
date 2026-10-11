package com.project.dacn.entity.catalog;

import com.project.dacn.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "attributes")
@SQLRestriction("is_deleted = false")
public class Attribute extends BaseEntity {

    @NaturalId
    @Column(name = "attribute_id", nullable = false, unique = true)
    private String attributeId;

    @Column(name = "attribute_name", nullable = false, unique = true)
    private String name;

    @Column(name = "is_deleted", nullable = false)
    @lombok.Builder.Default
    private Boolean deleted = false;

    @Override
    protected String getBusinessId() {
        return attributeId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.attributeId = businessId;
    }
}
