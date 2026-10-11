package com.project.dacn.entity;

import com.project.dacn.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "permissions")
public class Permission extends BaseEntity {

    @NaturalId
    @Column(name = "permission_id", nullable = false, unique = true)
    private String permissionId;

    @Column(name = "permission_code", nullable = false, unique = true, length = 100)
    private String code;

    @Column(name = "permission_name", nullable = false)
    private String name;

    @Column(name = "permission_resource", nullable = false, length = 50)
    private String resource;

    @Column(name = "permission_action", nullable = false, length = 50)
    private String action;

    @Column(name = "permission_description", columnDefinition = "TEXT")
    private String description;

    @Override
    protected String getBusinessId() {
        return permissionId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.permissionId = businessId;
    }
}
