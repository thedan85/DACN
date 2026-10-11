package com.project.dacn.modules.identity.entity;

import com.project.dacn.modules.auth.entity.User;
import com.project.dacn.modules.common.entity.BaseEntity;
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
@Table(name = "customers")
public class Customer extends BaseEntity {

    @NaturalId
    @Column(name = "customer_id", nullable = false, unique = true)
    private String customerId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", unique = true,
            foreignKey = @ForeignKey(name = "fk_customers_user"))
    private User user;

    @Column(name = "customer_name", nullable = false)
    private String name;

    @Column(name = "customer_address")
    private String address;

    @Column(name = "customer_phone", nullable = false, length = 20)
    private String phone;

    @Override
    protected String getBusinessId() {
        return customerId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.customerId = businessId;
    }
}
