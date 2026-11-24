package com.project.dacn.dto.response.engagement;

/** Owned DELETE mutation result: authoritative visible unread count and revision. */
public record NotificationCountResponse(
        long unreadCount,
        long revision) {
}
