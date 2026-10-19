package com.project.dacn.entity;

import com.project.dacn.common.util.BusinessId;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    protected Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    protected Instant updatedAt;

    protected String getBusinessId() {
        return null;
    }

    protected void setBusinessId(String businessId) {
    }

    protected String getBusinessIdPrefix() {
        return null;
    }

    /**
     * Default: {@code PREFIX_<8 hex>}. Entities with a unique natural key
     * override this to produce a readable ID.
     */
    protected String buildBusinessId() {
        return BusinessId.random(getBusinessIdPrefix());
    }

    @PrePersist
    protected void generateBusinessId() {
        if (getBusinessId() == null && getBusinessIdPrefix() != null) {
            setBusinessId(buildBusinessId());
        }
    }
}
