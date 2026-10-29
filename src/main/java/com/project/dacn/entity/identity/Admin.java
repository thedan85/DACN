package com.project.dacn.entity.identity;

import com.project.dacn.common.util.BusinessId;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.BaseEntity;
import com.project.dacn.common.constant.AdminStatus;
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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "admins")
public class Admin extends BaseEntity {

    @NaturalId
    @Column(name = "admin_id", nullable = false, unique = true)
    private String adminId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", unique = true,
            foreignKey = @ForeignKey(name = "fk_admins_user"))
    private User user;

    @Column(name = "admin_name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "admin_status", nullable = false, length = 20)
    @lombok.Builder.Default
    private AdminStatus status = AdminStatus.ACTIVE;

    @Override
    protected String getBusinessId() {
        return adminId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.adminId = businessId;
    }

    @Override
    protected String getBusinessIdPrefix() {
        return "ADM";
    }

    @Override
    protected String buildBusinessId() {
        return user != null && user.getEmail() != null
                ? BusinessId.ofUnique("ADM", user.getEmail())
                : BusinessId.random("ADM");
    }
}
