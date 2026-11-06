package com.project.dacn.messaging.outbox;

import java.util.UUID;

public record ClaimedOrderEvent(
        long id,
        UUID eventId,
        long orderPk,
        long orderSequence,
        String eventType,
        String payload,
        int attemptCount,
        UUID claimToken) {
}
