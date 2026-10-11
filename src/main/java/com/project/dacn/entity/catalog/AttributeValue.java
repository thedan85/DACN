package com.project.dacn.entity.catalog;

import com.project.dacn.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
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
@Table(name = "attribute_values", uniqueConstraints = {
        @UniqueConstraint(name = "uq_attribute_value", columnNames = {"attribute_id", "attribute_value"}),
        @UniqueConstraint(name = "uq_attribute_value_id", columnNames = {"attribute_id", "attribute_value_id"})
})
public class AttributeValue extends BaseEntity {

    @NaturalId
    @Column(name = "attribute_value_id", nullable = false, unique = true)
    private String attributeValueId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", referencedColumnName = "attribute_id",
            foreignKey = @ForeignKey(name = "fk_attribute_values_attribute"))
    private Attribute attribute;

    @Column(name = "attribute_value", nullable = false)
    private String value;

    @Override
    protected String getBusinessId() {
        return attributeValueId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.attributeValueId = businessId;
    }
}
