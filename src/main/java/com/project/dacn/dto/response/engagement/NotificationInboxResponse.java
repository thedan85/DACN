package com.project.dacn.dto.response.engagement;

import com.project.dacn.common.response.PageResponse;

/** Authorized inbox snapshot: one page plus the global visible unread count and revision. */
public record NotificationInboxResponse(
        PageResponse<NotificationResponse> notifications,
        long unreadCount,
        long revision) {
}
