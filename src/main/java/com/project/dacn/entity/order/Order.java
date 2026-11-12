package com.project.dacn.entity.order;

import com.project.dacn.entity.BaseEntity;
import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.event.OrderEventReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "orders")
public class Order extends BaseEntity {

    @NaturalId
    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_orders_customer"))
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 20)
    @lombok.Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "order_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Column(name = "order_address", nullable = false)
    private String address;

    @Column(name = "order_recipient_name", nullable = false)
    private String recipientName;

    @Column(name = "order_phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "event_sequence", nullable = false)
    private long eventSequence;

    @Column(name = "payment_expires_at")
    private Instant paymentExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 32)
    private OrderEventReason cancellationReason;

    @Column(name = "stock_released_at")
    private Instant stockReleasedAt;

    /** Inverse side of {@link Payment#getOrder()}; read-only. */
    @OneToOne(mappedBy = "order", fetch = FetchType.LAZY)
    private Payment payment;

    @Override
    protected String getBusinessId() {
        return orderId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.orderId = businessId;
    }

    @Override
    protected String getBusinessIdPrefix() {
        return "ORD";
    }
}
