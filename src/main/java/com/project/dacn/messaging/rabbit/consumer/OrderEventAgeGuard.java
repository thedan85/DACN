package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class OrderEventAgeGuard {

    public enum AgeDecision {
        OK, TOO_OLD, FUTURE
    }

    private static final long MAX_AGE_DAYS = 30;

    private final DatabaseClock databaseClock;

    public AgeDecision evaluate(OrderEvent event) {
        return evaluate(event, databaseClock.now());
    }

    public AgeDecision evaluate(OrderEvent event, Instant databaseNow) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(databaseNow, "databaseNow");
        Instant occurredAt = event.occurredAt();

        // Future time must never fall through to the age window.
        if (occurredAt.isAfter(databaseNow)) {
            return AgeDecision.FUTURE;
        }
        if (databaseNow.isBefore(occurredAt.plus(MAX_AGE_DAYS, ChronoUnit.DAYS))) {
            return AgeDecision.OK;
        }
        return AgeDecision.TOO_OLD;
    }
}
