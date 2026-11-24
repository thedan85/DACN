package com.project.dacn.entity.engagement;

import com.project.dacn.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "email_deliveries")
public class EmailDelivery extends BaseEntity {

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private EmailDeliveryState state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "next_eligible_at")
    private Instant nextEligibleAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;
}
