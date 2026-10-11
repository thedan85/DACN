package com.project.dacn.dto.response;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.common.constant.NotificationType;

import java.time.Instant;

public record NotificationResponse(
        String notificationId,
        String userId,
        String message,
        NotificationType type,
        NotificationStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
