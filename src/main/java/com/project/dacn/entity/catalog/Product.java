package com.project.dacn.entity.catalog;

import com.project.dacn.entity.BaseEntity;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.util.BusinessId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "products")
@SQLRestriction("product_status <> 'DELETED'")
public class Product extends BaseEntity {

    // product_id is rewritten by ProductService right after the insert
    // (PRD_<hex> from @PrePersist -> PRD_<surrogate id>), so it must be a
    // mutable natural id; Hibernate rejects immutable natural id changes.
    @NaturalId(mutable = true)
    @Column(name = "product_id", nullable = false, unique = true)
    private String productId;

    @Column(name = "product_name", nullable = false)
    private String name;

    @Column(name = "product_slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "product_description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "product_detail_description", columnDefinition = "TEXT")
    private String detailDescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thumbnail_media_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_products_thumbnail_media"))
    private Media thumbnail;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_status", nullable = false, length = 20)
    @lombok.Builder.Default
    private ProductStatus status = ProductStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_products_category"))
    private Category category;

    @Override
    protected String getBusinessId() {
        return productId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.productId = businessId;
    }

    @Override
    protected String getBusinessIdPrefix() {
        return "PRD";
    }

    /**
     * Safety net for direct repository writes (seeds/tests). ProductService
     * generates a collision-free slug before saving; this only fills a gap.
     */
    @PrePersist
    void generateSlug() {
        if (slug == null || slug.isBlank()) {
            slug = BusinessId.slug(name);
        }
    }

    // product_id is derived from the surrogate id (PRD_<id>), so it cannot be
    // built in @PrePersist: with GenerationType.IDENTITY the id only exists
    // after the insert. ProductService rewrites it right after saveAndFlush.
}
