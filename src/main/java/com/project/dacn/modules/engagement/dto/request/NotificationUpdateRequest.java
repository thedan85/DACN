package com.project.dacn.modules.engagement.dto.request;

import com.project.dacn.modules.common.enums.NotificationStatus;

public record NotificationUpdateRequest(
        NotificationStatus status,
        Boolean deleted) {
}
