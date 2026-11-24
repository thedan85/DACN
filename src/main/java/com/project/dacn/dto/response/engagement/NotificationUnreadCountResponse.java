package com.project.dacn.dto.response.engagement;

/** Bell-only snapshot: global visible unread count and inbox revision, no message content. */
public record NotificationUnreadCountResponse(
        long unreadCount,
        long revision) {
}
