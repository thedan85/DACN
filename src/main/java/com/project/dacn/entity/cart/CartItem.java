package com.project.dacn.entity.cart;

import com.project.dacn.entity.catalog.Variant;
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
@Table(name = "cart_items",
        uniqueConstraints = @UniqueConstraint(name = "uq_cart_variant",
                columnNames = {"cart_id", "variant_id"}))
public class CartItem extends BaseEntity {

    @NaturalId
    @Column(name = "cart_item_id", nullable = false, unique = true)
    private String cartItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_cart_items_cart"))
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_cart_items_variant"))
    private Variant variant;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Override
    protected String getBusinessId() {
        return cartItemId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.cartItemId = businessId;
    }

    @Override
    protected String getBusinessIdPrefix() {
        return "CI";
    }
}
