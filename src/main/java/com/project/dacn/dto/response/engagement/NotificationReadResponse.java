package com.project.dacn.dto.response.engagement;

/** Owned READ mutation result: the saved row plus authoritative inbox state. */
public record NotificationReadResponse(
        NotificationResponse notification,
        long unreadCount,
        long revision) {
}
