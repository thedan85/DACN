package com.project.dacn.entity.catalog;

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
@Table(name = "categories")
@SQLRestriction("is_deleted = false")
public class Category extends BaseEntity {

    @NaturalId
    @Column(name = "category_id", nullable = false, unique = true)
    private String categoryId;

    @Column(name = "category_name", nullable = false)
    private String name;

    @Column(name = "category_slug", nullable = false, unique = true)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_category_id", referencedColumnName = "category_id",
            foreignKey = @ForeignKey(name = "fk_categories_parent"))
    private Category parent;

    @Column(name = "is_deleted", nullable = false)
    @lombok.Builder.Default
    private Boolean deleted = false;

    @Override
    protected String getBusinessId() {
        return categoryId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.categoryId = businessId;
    }
}
