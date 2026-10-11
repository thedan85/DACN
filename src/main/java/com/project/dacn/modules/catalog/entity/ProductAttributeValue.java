package com.project.dacn.modules.catalog.entity;

import com.project.dacn.modules.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.NaturalId;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "product_attribute_values",
        uniqueConstraints = @UniqueConstraint(name = "uq_product_attribute",
                columnNames = {"product_id", "attribute_id"}))
public class ProductAttributeValue extends BaseEntity {

    @NaturalId
    @Column(name = "product_value_id", nullable = false, unique = true)
    private String productValueId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", referencedColumnName = "product_id",
            foreignKey = @ForeignKey(name = "fk_product_values_product"))
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", referencedColumnName = "attribute_id")
    private Attribute attribute;

    @Column(name = "attribute_value_id", nullable = false)
    private String attributeValueId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "attribute_id", referencedColumnName = "attribute_id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "attribute_value_id", referencedColumnName = "attribute_value_id",
                    insertable = false, updatable = false,
                    foreignKey = @ForeignKey(name = "fk_product_values_attr_pair"))
    })
    private AttributeValue attributeValue;

    public void setAttributeValue(AttributeValue attributeValue) {
        this.attributeValue = attributeValue;
        this.attributeValueId = attributeValue != null ? attributeValue.getAttributeValueId() : null;
    }

    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    private void syncAttributeValueId() {
        if (attributeValue != null && attributeValue.getAttributeValueId() != null) {
            this.attributeValueId = attributeValue.getAttributeValueId();
        }
    }

    @Override
    protected String getBusinessId() {
        return productValueId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.productValueId = businessId;
    }
}
