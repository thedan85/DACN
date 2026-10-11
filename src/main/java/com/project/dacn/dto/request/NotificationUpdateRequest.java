package com.project.dacn.dto.request;

import com.project.dacn.common.constant.NotificationStatus;

public record NotificationUpdateRequest(
        NotificationStatus status,
        Boolean deleted) {
}
