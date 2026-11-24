package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderEventAgeGuardTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-04T00:00:00Z");

    private final OrderEventAgeGuard guard = new OrderEventAgeGuard(mock(DatabaseClock.class));

    @Test
    void justBeforeTheCutoffIsOk() {
        Instant databaseNow = OCCURRED_AT.plus(30, ChronoUnit.DAYS).minusNanos(1);

        assertThat(guard.evaluate(event(), databaseNow))
                .isEqualTo(OrderEventAgeGuard.AgeDecision.OK);
    }

    @Test
    void exactlyAtTheCutoffIsTooOld() {
        Instant databaseNow = OCCURRED_AT.plus(30, ChronoUnit.DAYS);

        assertThat(guard.evaluate(event(), databaseNow))
                .isEqualTo(OrderEventAgeGuard.AgeDecision.TOO_OLD);
    }

    @Test
    void afterTheCutoffIsTooOld() {
        Instant databaseNow = OCCURRED_AT.plus(30, ChronoUnit.DAYS).plusNanos(1);

        assertThat(guard.evaluate(event(), databaseNow))
                .isEqualTo(OrderEventAgeGuard.AgeDecision.TOO_OLD);
    }

    @Test
    void futureOccurrenceIsFutureEvenBeyondTheCutoff() {
        assertThat(guard.evaluate(event(), OCCURRED_AT.minusNanos(1)))
                .isEqualTo(OrderEventAgeGuard.AgeDecision.FUTURE);
        assertThat(guard.evaluate(event(), OCCURRED_AT.minus(90, ChronoUnit.DAYS)))
                .isEqualTo(OrderEventAgeGuard.AgeDecision.FUTURE);
    }

    @Test
    void singleArgumentEvaluateUsesTheDatabaseClock() {
        DatabaseClock clock = mock(DatabaseClock.class);
        when(clock.now()).thenReturn(OCCURRED_AT.plus(29, ChronoUnit.DAYS));

        assertThat(new OrderEventAgeGuard(clock).evaluate(event()))
                .isEqualTo(OrderEventAgeGuard.AgeDecision.OK);
        verify(clock, times(1)).now();

        when(clock.now()).thenReturn(OCCURRED_AT.plus(30, ChronoUnit.DAYS));

        assertThat(new OrderEventAgeGuard(clock).evaluate(event()))
                .isEqualTo(OrderEventAgeGuard.AgeDecision.TOO_OLD);
        verify(clock, times(2)).now();
    }

    private static OrderEvent event() {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, OCCURRED_AT, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                new BigDecimal("199.90"), null, "corr-123", "CHECKOUT_WEB");
    }
}
