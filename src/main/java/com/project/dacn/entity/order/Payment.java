package com.project.dacn.entity.order;

import com.project.dacn.entity.BaseEntity;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @NaturalId
    @Column(name = "payment_id", nullable = false, unique = true)
    private String paymentId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", referencedColumnName = "order_id", unique = true,
            foreignKey = @ForeignKey(name = "fk_payments_order"))
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    @lombok.Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "payment_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_transaction_id", unique = true)
    private String transactionId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Override
    protected String getBusinessId() {
        return paymentId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.paymentId = businessId;
    }
}
