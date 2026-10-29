package com.project.dacn.entity.auth;

import com.project.dacn.common.util.BusinessId;
import com.project.dacn.entity.BaseEntity;
import com.project.dacn.common.constant.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.Instant;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "users")
@SQLRestriction("user_status <> 'DELETED'")
public class User extends BaseEntity {

    @NaturalId
    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    @Column(name = "user_email", nullable = false, unique = true)
    private String email;

    @Column(name = "user_password_hash")
    private String passwordHash;

    @Column(name = "google_sub", unique = true)
    private String googleSub;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_status", nullable = false, length = 20)
    @lombok.Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_users_role"))
    private Role role;

    @Column(name = "user_last_login")
    private Instant lastLogin;

    @Override
    protected String getBusinessId() {
        return userId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.userId = businessId;
    }

    @Override
    protected String getBusinessIdPrefix() {
        return "USR";
    }

    @Override
    protected String buildBusinessId() {
        return BusinessId.ofUnique("USR", email);
    }
}
