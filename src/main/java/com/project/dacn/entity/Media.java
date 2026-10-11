package com.project.dacn.entity;

import com.project.dacn.entity.BaseEntity;
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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "media")
@SQLRestriction("is_deleted = false")
public class Media extends BaseEntity {

    @NaturalId
    @Column(name = "media_id", nullable = false, unique = true)
    private String mediaId;

    @Column(name = "media_url", nullable = false, length = 500)
    private String url;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", referencedColumnName = "product_id",
            foreignKey = @ForeignKey(name = "fk_media_product"))
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", referencedColumnName = "variant_id",
            foreignKey = @ForeignKey(name = "fk_media_variant"))
    private Variant variant;

    @Column(name = "is_deleted", nullable = false)
    @lombok.Builder.Default
    private Boolean deleted = false;

    @Override
    protected String getBusinessId() {
        return mediaId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.mediaId = businessId;
    }
}
