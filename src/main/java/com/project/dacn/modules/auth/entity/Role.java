package com.project.dacn.modules.auth.entity;

import com.project.dacn.modules.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "roles")
@SQLRestriction("is_deleted = false")
public class Role extends BaseEntity {

    @NaturalId
    @Column(name = "role_id", nullable = false, unique = true)
    private String roleId;

    @Column(name = "role_code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "role_name", nullable = false)
    private String name;

    @Column(name = "role_description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "role_is_system", nullable = false)
    @lombok.Builder.Default
    private Boolean system = false;

    @Column(name = "is_deleted", nullable = false)
    @lombok.Builder.Default
    private Boolean deleted = false;

    @Override
    protected String getBusinessId() {
        return roleId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.roleId = businessId;
    }
}
