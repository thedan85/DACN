package com.project.dacn.modules.engagement.dto.response;

import com.project.dacn.modules.common.enums.NotificationStatus;
import com.project.dacn.modules.common.enums.NotificationType;

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
