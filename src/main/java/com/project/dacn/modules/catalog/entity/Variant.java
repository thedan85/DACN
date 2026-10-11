package com.project.dacn.modules.catalog.entity;

import com.project.dacn.modules.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "variants")
@SQLRestriction("is_deleted = false")
public class Variant extends BaseEntity {

    @NaturalId
    @Column(name = "variant_id", nullable = false, unique = true)
    private String variantId;

    @Column(name = "variant_sku", nullable = false, unique = true)
    private String sku;

    @Column(name = "variant_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "variant_stock", nullable = false)
    @lombok.Builder.Default
    private Integer stock = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", referencedColumnName = "product_id",
            foreignKey = @ForeignKey(name = "fk_variants_product"))
    private Product product;

    @Column(name = "is_deleted", nullable = false)
    @lombok.Builder.Default
    private Boolean deleted = false;

    @Override
    protected String getBusinessId() {
        return variantId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.variantId = businessId;
    }
}
