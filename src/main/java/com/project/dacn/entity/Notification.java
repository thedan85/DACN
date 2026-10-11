package com.project.dacn.entity;

import com.project.dacn.entity.User;
import com.project.dacn.entity.BaseEntity;
import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.common.constant.NotificationType;
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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "notifications")
@SQLRestriction("is_deleted = false")
public class Notification extends BaseEntity {

    @NaturalId
    @Column(name = "notification_id", nullable = false, unique = true)
    private String notificationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id",
            foreignKey = @ForeignKey(name = "fk_notifications_user"))
    private User user;

    @Column(name = "notification_message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 20)
    @lombok.Builder.Default
    private NotificationType type = NotificationType.SYSTEM;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_status", nullable = false, length = 20)
    @lombok.Builder.Default
    private NotificationStatus status = NotificationStatus.UNREAD;

    @Column(name = "is_deleted", nullable = false)
    @lombok.Builder.Default
    private Boolean deleted = false;

    @Override
    protected String getBusinessId() {
        return notificationId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.notificationId = businessId;
    }
}
