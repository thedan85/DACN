package com.project.dacn.modules.common.entity;

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
import java.util.UUID;

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

    protected abstract String getBusinessId();

    protected abstract void setBusinessId(String businessId);

    @PrePersist
    protected void generateBusinessId() {
        if (getBusinessId() == null) {
            setBusinessId(UUID.randomUUID().toString());
        }
    }
}
