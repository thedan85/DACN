package com.project.dacn.entity.engagement;

import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.BaseEntity;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.OrderItem;
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
@Table(name = "reviews", uniqueConstraints = {
        @UniqueConstraint(name = "uq_reviews_customer_variant",
                columnNames = {"customer_id", "variant_id"}),
        @UniqueConstraint(name = "uq_reviews_order_item", columnNames = "order_item_id")
})
public class Review extends BaseEntity {

    @NaturalId
    @Column(name = "review_id", nullable = false, unique = true)
    private String reviewId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_reviews_variant"))
    private Variant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_reviews_customer"))
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_reviews_order_item"))
    private OrderItem orderItem;

    @Column(name = "review_rating", nullable = false)
    private Integer rating;

    @Column(name = "review_comment", nullable = false, columnDefinition = "TEXT")
    private String comment;

    @Override
    protected String getBusinessId() {
        return reviewId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.reviewId = businessId;
    }

    @Override
    protected String getBusinessIdPrefix() {
        return "REV";
    }
}
