package com.project.dacn.entity.auth;

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

import java.time.Instant;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @NaturalId
    @Column(name = "token_id", nullable = false, unique = true)
    private String tokenId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id",
            foreignKey = @ForeignKey(name = "fk_refresh_tokens_user"))
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "token_expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "token_revoked", nullable = false)
    @lombok.Builder.Default
    private Boolean revoked = false;

    @Column(name = "token_user_agent")
    private String userAgent;

    @Column(name = "token_ip", length = 45)
    private String ip;

    @Override
    protected String getBusinessId() {
        return tokenId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.tokenId = businessId;
    }

    @Override
    protected String getBusinessIdPrefix() {
        return "RT";
    }
}
