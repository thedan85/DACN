package com.project.dacn.modules.order.entity;

import com.project.dacn.modules.catalog.entity.Variant;
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

import java.math.BigDecimal;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    @NaturalId
    @Column(name = "order_item_id", nullable = false, unique = true)
    private String orderItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", referencedColumnName = "order_id",
            foreignKey = @ForeignKey(name = "fk_order_items_order"))
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", referencedColumnName = "variant_id",
            foreignKey = @ForeignKey(name = "fk_order_items_variant"))
    private Variant variant;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Override
    protected String getBusinessId() {
        return orderItemId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.orderItemId = businessId;
    }
}
