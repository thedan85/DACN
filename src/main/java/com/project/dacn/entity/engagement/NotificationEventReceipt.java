package com.project.dacn.entity.engagement;

import com.project.dacn.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "notification_event_receipts")
public class NotificationEventReceipt extends BaseEntity {

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    /** Recipient set frozen at first successful fan-out; replays must not widen it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipient_user_ids", nullable = false)
    private List<Long> recipientUserIds;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;
}
