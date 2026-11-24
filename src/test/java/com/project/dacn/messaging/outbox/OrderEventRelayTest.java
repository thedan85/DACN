package com.project.dacn.messaging.outbox;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.EventPublishException;
import com.project.dacn.messaging.OrderEventPublisher;
import com.project.dacn.messaging.rabbit.RabbitDeliveryStartup;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderEventRelayTest {

    private static final Instant CLAIM_NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final OutboxRelayRepository repository = mock(OutboxRelayRepository.class);
    private final OrderEventCodec codec = mock(OrderEventCodec.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final OrderEventPublisher publisher = mock(OrderEventPublisher.class);
    private final OutboxTelemetry telemetry = mock(OutboxTelemetry.class);
    private final RabbitDeliveryStartup deliveryStartup = mock(RabbitDeliveryStartup.class);
    private final OrderEventRelay relay = new OrderEventRelay(
            repository, codec, clock, new OrderEventAgeGuard(clock), publisher, telemetry, deliveryStartup);

    @BeforeEach
    void defaults() {
        when(deliveryStartup.isReady()).thenReturn(true);
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any())).thenReturn(List.of());
        when(repository.markPublished(anyLong(), any(), any())).thenReturn(true);
        when(repository.releaseForRetry(anyLong(), any(), any(), anyString())).thenReturn(true);
        when(repository.quarantine(anyLong(), any(), any(), anyString())).thenReturn(true);
        when(clock.now()).thenReturn(CLAIM_NOW);
    }

    @Test
    void claimsOneBatchOfTwentyWithSixtySecondLeaseAndFreshOwnerAndTokenPerPoll() {
        relay.poll();
        relay.poll();

        ArgumentCaptor<String> owners = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> tokens = ArgumentCaptor.forClass(UUID.class);
        verify(repository, times(2)).claimDue(
                eq(20), eq(CLAIM_NOW), eq(LEASE), owners.capture(), tokens.capture());

        assertThat(owners.getAllValues()).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(tokens.getAllValues()).doesNotHaveDuplicates().doesNotContainNull();
    }

    @Test
    void successfulPublishMarksPublishedWithTheClaimTokenAndDoesNotRetry() {
        ClaimedOrderEvent row = row(1L, 1);
        OrderEvent event = eventAt(CLAIM_NOW);
        Instant rowNow = CLAIM_NOW.plusSeconds(1);
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any())).thenReturn(List.of(row));
        when(codec.decode(any())).thenReturn(event);
        when(clock.now()).thenReturn(CLAIM_NOW, rowNow);

        relay.poll();

        verify(publisher).publish(event);
        verify(repository).markPublished(row.id(), row.claimToken(), rowNow);
        verify(repository, never()).releaseForRetry(anyLong(), any(), any(), anyString());
        verify(repository, never()).quarantine(anyLong(), any(), any(), anyString());
        verify(telemetry).recordPublishSuccess(any(Duration.class));
        verify(telemetry, never()).recordPublishFailure(any(Duration.class));
    }

    @ParameterizedTest
    @CsvSource({"1,5", "2,10", "3,20", "4,40", "5,60", "6,60"})
    void publishFailureReleasesForRetryWithCappedExponentialBackoff(int attemptCount, long expectedSeconds) {
        ClaimedOrderEvent row = row(1L, attemptCount);
        OrderEvent event = eventAt(CLAIM_NOW);
        Instant rowNow = CLAIM_NOW.plusSeconds(1);
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any())).thenReturn(List.of(row));
        when(codec.decode(any())).thenReturn(event);
        when(clock.now()).thenReturn(CLAIM_NOW, rowNow);
        doThrow(new EventPublishException(EventPublishException.ReasonCode.PUBLISH_NACK))
                .when(publisher).publish(event);

        relay.poll();

        verify(repository).releaseForRetry(row.id(), row.claimToken(),
                rowNow.plusSeconds(expectedSeconds), "PUBLISH_FAILED");
        verify(repository, never()).markPublished(anyLong(), any(), any());
        verify(repository, never()).quarantine(anyLong(), any(), any(), anyString());
        verify(telemetry).recordPublishFailure(any(Duration.class));
        verify(telemetry, never()).recordPublishSuccess(any(Duration.class));
    }

    @Test
    void exactlyThirtyDaysOldIsQuarantinedAndNeverPublished() {
        assertQuarantined(eventAt(CLAIM_NOW.minus(30, ChronoUnit.DAYS)), "EVENT_TOO_OLD");
    }

    @Test
    void olderThanThirtyDaysIsQuarantinedAndNeverPublished() {
        assertQuarantined(eventAt(CLAIM_NOW.minus(30, ChronoUnit.DAYS).minusSeconds(1)),
                "EVENT_TOO_OLD");
    }

    @Test
    void futureOccurrenceIsQuarantinedAndNeverPublished() {
        assertQuarantined(eventAt(CLAIM_NOW.plusNanos(1)), "EVENT_FUTURE");
    }

    @Test
    void codecFailureIsQuarantinedAsInvalidAndNeverPublished() {
        ClaimedOrderEvent row = row(1L, 1);
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any())).thenReturn(List.of(row));
        when(codec.decode(any())).thenThrow(new OrderEventCodecException(
                OrderEventCodecException.ReasonCode.EVENT_INVALID));

        relay.poll();

        verify(repository).quarantine(row.id(), row.claimToken(), CLAIM_NOW, "EVENT_INVALID");
        verify(publisher, never()).publish(any());
        verify(repository, never()).markPublished(anyLong(), any(), any());
        verify(repository, never()).releaseForRetry(anyLong(), any(), any(), anyString());
        verify(telemetry).recordQuarantine();
    }

    @Test
    void staleTokenMarkPublishedResultDoesNotThrowOrRetry() {
        ClaimedOrderEvent row = row(1L, 1);
        OrderEvent event = eventAt(CLAIM_NOW);
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any())).thenReturn(List.of(row));
        when(codec.decode(any())).thenReturn(event);
        when(repository.markPublished(anyLong(), any(), any())).thenReturn(false);

        assertThatCode(relay::poll).doesNotThrowAnyException();

        verify(repository).markPublished(eq(row.id()), eq(row.claimToken()), any());
        verify(repository, never()).releaseForRetry(anyLong(), any(), any(), anyString());
        verify(repository, never()).quarantine(anyLong(), any(), any(), anyString());
    }

    @Test
    void ageIsEvaluatedAgainstFreshDatabaseTimeForEachRow() {
        ClaimedOrderEvent first = row(1L, 1);
        ClaimedOrderEvent second = row(2L, 1);
        OrderEvent event = eventAt(CLAIM_NOW);
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any()))
                .thenReturn(List.of(first, second));
        when(codec.decode(any())).thenReturn(event);
        when(clock.now()).thenReturn(
                CLAIM_NOW,
                CLAIM_NOW.plusSeconds(1),
                CLAIM_NOW.plusSeconds(1),
                CLAIM_NOW.plus(31, ChronoUnit.DAYS),
                CLAIM_NOW.plus(31, ChronoUnit.DAYS));

        relay.poll();

        verify(repository).markPublished(first.id(), first.claimToken(), CLAIM_NOW.plusSeconds(1));
        verify(repository).quarantine(second.id(), second.claimToken(),
                CLAIM_NOW.plus(31, ChronoUnit.DAYS), "EVENT_TOO_OLD");
        verify(publisher, times(1)).publish(event);
    }

    @Test
    void oneFailingRowDoesNotStopLaterRowsInTheBatch() {
        ClaimedOrderEvent first = row(1L, 1);
        ClaimedOrderEvent second = row(2L, 1);
        OrderEvent firstEvent = eventAt(CLAIM_NOW);
        OrderEvent secondEvent = eventAt(CLAIM_NOW);
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any()))
                .thenReturn(List.of(first, second));
        when(codec.decode(any())).thenReturn(firstEvent, secondEvent);
        doThrow(new EventPublishException(EventPublishException.ReasonCode.BROKER_UNAVAILABLE))
                .when(publisher).publish(firstEvent);
        when(clock.now()).thenReturn(
                CLAIM_NOW,
                CLAIM_NOW.plusSeconds(1),
                CLAIM_NOW.plusSeconds(1),
                CLAIM_NOW.plusSeconds(2),
                CLAIM_NOW.plusSeconds(2));

        relay.poll();

        verify(repository).releaseForRetry(eq(first.id()), eq(first.claimToken()), any(), eq("PUBLISH_FAILED"));
        verify(repository).markPublished(second.id(), second.claimToken(), CLAIM_NOW.plusSeconds(2));
        verify(publisher).publish(firstEvent);
        verify(publisher).publish(secondEvent);
    }

    @Test
    void unexpectedCodecRuntimeExceptionDoesNotStopLaterRowsInTheBatch() {
        ClaimedOrderEvent first = row(1L, 1);
        ClaimedOrderEvent second = row(2L, 1);
        OrderEvent secondEvent = eventAt(CLAIM_NOW);
        Instant secondNow = CLAIM_NOW.plusSeconds(1);
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any()))
                .thenReturn(List.of(first, second));
        when(codec.decode(any()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(secondEvent);
        when(clock.now()).thenReturn(CLAIM_NOW, secondNow, secondNow);

        assertThatCode(relay::poll).doesNotThrowAnyException();

        verify(publisher).publish(secondEvent);
        verify(repository).markPublished(second.id(), second.claimToken(), secondNow);
        verify(repository, never()).markPublished(eq(first.id()), any(), any());
        verify(telemetry, times(1)).recordPublishSuccess(any(Duration.class));
    }

    @Test
    void relayBeanIsAbsentWhenDeliveryIsDisabled() {
        runner().withUserConfiguration(OrderEventRelay.class)
                .withPropertyValues("app.notifications.delivery-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OrderEventRelay.class));
    }

    @Test
    void relayBeanIsPresentWhenDeliveryIsEnabled() {
        runner().withUserConfiguration(OrderEventRelay.class)
                .withPropertyValues("app.notifications.delivery-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(OrderEventRelay.class));
    }

    private void assertQuarantined(OrderEvent event, String expectedCode) {
        ClaimedOrderEvent row = row(1L, 1);
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any())).thenReturn(List.of(row));
        when(codec.decode(any())).thenReturn(event);

        relay.poll();

        verify(repository).quarantine(row.id(), row.claimToken(), CLAIM_NOW, expectedCode);
        verify(publisher, never()).publish(any());
        verify(repository, never()).markPublished(anyLong(), any(), any());
        verify(repository, never()).releaseForRetry(anyLong(), any(), any(), anyString());
        verify(telemetry).recordQuarantine();
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(OutboxRelayRepository.class, () -> mock(OutboxRelayRepository.class))
                .withBean(OrderEventCodec.class, () -> mock(OrderEventCodec.class))
                .withBean(DatabaseClock.class, () -> mock(DatabaseClock.class))
                .withBean(OrderEventAgeGuard.class, () -> mock(OrderEventAgeGuard.class))
                .withBean(OrderEventPublisher.class, () -> mock(OrderEventPublisher.class))
                .withBean(RabbitDeliveryStartup.class, () -> mock(RabbitDeliveryStartup.class))
                .withBean(OutboxTelemetry.class, () -> mock(OutboxTelemetry.class));
    }

    private static ClaimedOrderEvent row(long id, int attemptCount) {
        return new ClaimedOrderEvent(id, UUID.randomUUID(), id + 100, 1L, "ORDER_PLACED",
                "{}", attemptCount, UUID.randomUUID());
    }

    private static OrderEvent eventAt(Instant occurredAt) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_123", 1L, occurredAt, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                new BigDecimal("199.90"), null, "corr-123", "CHECKOUT_WEB");
    }
}
