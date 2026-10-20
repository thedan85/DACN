package com.project.dacn.entity.catalog;

import com.project.dacn.common.util.BusinessId;
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
@Table(name = "product_attribute_values",
        uniqueConstraints = @UniqueConstraint(name = "uq_product_attribute",
                columnNames = {"product_id", "attribute_id"}))
public class ProductAttributeValue extends BaseEntity {

    @NaturalId
    @Column(name = "product_value_id", nullable = false, unique = true)
    private String productValueId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_product_values_product"))
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", referencedColumnName = "id")
    private Attribute attribute;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_value_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_product_values_attr_pair"))
    private AttributeValue attributeValue;

    @Override
    protected String getBusinessId() {
        return productValueId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.productValueId = businessId;
    }

    @Override
    protected String getBusinessIdPrefix() {
        return "PAV";
    }

    /**
     * Matches the seeded IDs: {@code <productId>-<attributeId>}, e.g.
     * {@code PRD_7-temperature}.
     */
    @Override
    protected String buildBusinessId() {
        if (product != null && product.getProductId() != null
                && attribute != null && attribute.getAttributeId() != null) {
            return product.getProductId() + "-" + attribute.getAttributeId();
        }
        return BusinessId.randomToken();
    }
}
