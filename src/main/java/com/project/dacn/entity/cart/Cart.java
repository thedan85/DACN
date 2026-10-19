package com.project.dacn.entity.cart;

import com.project.dacn.common.util.BusinessId;
import com.project.dacn.entity.BaseEntity;
import com.project.dacn.entity.identity.Customer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
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
@Table(name = "carts")
public class Cart extends BaseEntity {

    @NaturalId
    @Column(name = "cart_id", nullable = false, unique = true)
    private String cartId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", referencedColumnName = "customer_id", unique = true,
            foreignKey = @ForeignKey(name = "fk_carts_customer"))
    private Customer customer;

    @Override
    protected String getBusinessId() {
        return cartId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.cartId = businessId;
    }

    @Override
    protected String getBusinessIdPrefix() {
        return "CART";
    }

    @Override
    protected String buildBusinessId() {
        return customer != null && customer.getCustomerId() != null
                ? BusinessId.child("CART", customer.getCustomerId())
                : BusinessId.random("CART");
    }
}
