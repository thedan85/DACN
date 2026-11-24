package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.rabbit.NotificationRabbitProperties;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationConsumerSupportTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:15:30Z");
    private static final long DELIVERY_TAG = 7L;
    private static final byte[] BODY =
            "{\"eventId\":\"evt-123\"}".getBytes(StandardCharsets.UTF_8);

    private RabbitOperations operations;
    private com.rabbitmq.client.Channel amqpChannel;
    private NotificationConsumerSupport support;
    private List<Sent> sent;
    private Consumer<CorrelationData> confirmBehavior;

    @BeforeEach
    void setUp() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        operations = mock(RabbitOperations.class);
        amqpChannel = mock(com.rabbitmq.client.Channel.class);
        sent = new ArrayList<>();
        confirmBehavior = correlationData ->
                correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));

        NotificationRabbitProperties properties = new NotificationRabbitProperties();
        properties.setConfirmTimeout(Duration.ofMillis(200));
        support = new NotificationConsumerSupport(
                new RabbitRetryPublisher(rabbitTemplate, properties),
                new OrderEventAgeGuard(clockAt(NOW)));

        when(rabbitTemplate.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(operations);
        });
        doAnswer(invocation -> {
            sent.add(new Sent(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2)));
            confirmBehavior.accept(invocation.getArgument(3));
            return null;
        }).when(operations).send(anyString(), anyString(), any(Message.class),
                any(CorrelationData.class));
    }

    @Test
    void unsupportedRetryDelayIsRejected() {
        assertThatThrownBy(() -> NotificationChannel.INAPP.retryQueue(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationChannel.EMAIL.retryQueue(Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attemptZeroRetriesAfterTenSecondsWithHeaderOneAndAcksAfterThePublish() throws Exception {
        assertRetry(0, "dacn.notifications.inapp.retry.10s", 1);
    }

    @Test
    void attemptOneRetriesAfterThirtySecondsWithHeaderTwo() throws Exception {
        assertRetry(1, "dacn.notifications.inapp.retry.30s", 2);
    }

    @Test
    void attemptTwoRetriesAfterOneHundredTwentySecondsWithHeaderThree() throws Exception {
        assertRetry(2, "dacn.notifications.inapp.retry.120s", 3);
    }

    @Test
    void absentAttemptHeaderIsTreatedAsZero() throws Exception {
        assertRetry(-1, "dacn.notifications.inapp.retry.10s", 1);
    }

    @Test
    void attemptThreeGoesToTheDlqWithoutARetryPublish() throws Exception {
        support.handleTransientFailure(NotificationChannel.INAPP, message(3), amqpChannel,
                event(NOW), new IllegalStateException("boom"));

        assertThat(sent).singleElement().satisfies(published -> {
            assertThat(published.exchange()).isEqualTo(RabbitTopology.DLX);
            assertThat(published.routingKey()).isEqualTo("dacn.notifications.inapp.dlq");
            assertThat(reason(published.message())).isEqualTo("RETRIES_EXHAUSTED");
            assertThat(retryAttempt(published.message())).isEqualTo(3);
        });
        verify(operations, times(1)).send(anyString(), anyString(), any(Message.class),
                any(CorrelationData.class));
        verify(amqpChannel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void invalidEventWithANullPayloadObjectGoesToTheDlq() throws Exception {
        support.handlePermanentFailure(NotificationChannel.EMAIL, message(-1), amqpChannel,
                null, "EVENT_INVALID");

        assertThat(sent).singleElement().satisfies(published -> {
            assertThat(published.exchange()).isEqualTo(RabbitTopology.DLX);
            assertThat(published.routingKey()).isEqualTo("dacn.notifications.email.dlq");
            assertThat(reason(published.message())).isEqualTo("EVENT_INVALID");
            assertThat(published.message().getBody()).isEqualTo(BODY);
            assertThat((Object) published.message().getMessageProperties()
                    .getHeader(RabbitRetryPublisher.RETRY_ATTEMPT_HEADER)).isNull();
        });
        verify(amqpChannel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void deadLetterForwardingDropsANonNumericAttemptHeader() throws Exception {
        Message original = message(-1);
        original.getMessageProperties().setHeader(
                RabbitRetryPublisher.RETRY_ATTEMPT_HEADER, "not-a-number");

        support.handlePermanentFailure(NotificationChannel.EMAIL, original, amqpChannel,
                null, "EVENT_INVALID");

        Message forwarded = sent.getFirst().message();
        assertThat((Object) forwarded.getMessageProperties()
                .getHeader(RabbitRetryPublisher.RETRY_ATTEMPT_HEADER)).isNull();
        assertThat(reason(forwarded)).isEqualTo("EVENT_INVALID");
        verify(amqpChannel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void eventAtExactlyThirtyDaysIsQuarantinedAsTooOld() throws Exception {
        OrderEvent tooOld = event(NOW.minus(30, ChronoUnit.DAYS));

        boolean rejected = support.rejectIfOutsideWindow(NotificationChannel.INAPP,
                message(0), amqpChannel, tooOld);

        assertThat(rejected).isTrue();
        assertThat(sent).singleElement().satisfies(published -> {
            assertThat(published.exchange()).isEqualTo(RabbitTopology.DLX);
            assertThat(published.routingKey()).isEqualTo("dacn.notifications.inapp.dlq");
            assertThat(reason(published.message())).isEqualTo("EVENT_TOO_OLD");
        });
        verify(operations, times(1)).send(anyString(), anyString(), any(Message.class),
                any(CorrelationData.class));
        verify(amqpChannel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void futureEventIsQuarantinedAsFuture() throws Exception {
        OrderEvent future = event(NOW.plusSeconds(1));

        boolean rejected = support.rejectIfOutsideWindow(NotificationChannel.INAPP,
                message(0), amqpChannel, future);

        assertThat(rejected).isTrue();
        assertThat(sent).singleElement().satisfies(published -> {
            assertThat(reason(published.message())).isEqualTo("EVENT_FUTURE");
            assertThat(published.exchange()).isEqualTo(RabbitTopology.DLX);
        });
    }

    @Test
    void eventInsideTheWindowIsNotRejectedAndIsNotAcknowledged() throws Exception {
        boolean rejected = support.rejectIfOutsideWindow(NotificationChannel.INAPP,
                message(0), amqpChannel, event(NOW));

        assertThat(rejected).isFalse();
        assertThat(sent).isEmpty();
        verify(amqpChannel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void publishNackLeavesTheMessageUnacknowledged() throws Exception {
        confirmBehavior = correlationData ->
                correlationData.getFuture().complete(new CorrelationData.Confirm(false, "nack"));

        assertThatThrownBy(() -> support.handleTransientFailure(NotificationChannel.INAPP,
                message(0), amqpChannel, event(NOW), new IllegalStateException("boom")))
                .isInstanceOfSatisfying(RetryTransferFailedException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(RetryTransferFailedException.ReasonCode.PUBLISH_NACK));
        verify(amqpChannel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void mandatoryReturnLeavesTheMessageUnacknowledged() throws Exception {
        confirmBehavior = correlationData -> {
            correlationData.setReturned(new ReturnedMessage(new Message(new byte[0]), 312,
                    "NO_ROUTE", RabbitTopology.RETRY_EXCHANGE,
                    "dacn.notifications.inapp.retry.10s"));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
        };

        assertThatThrownBy(() -> support.handlePermanentFailure(NotificationChannel.INAPP,
                message(0), amqpChannel, event(NOW), "RETRIES_EXHAUSTED"))
                .isInstanceOfSatisfying(RetryTransferFailedException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                RetryTransferFailedException.ReasonCode.PUBLISH_UNROUTABLE));
        verify(amqpChannel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void confirmTimeoutLeavesTheMessageUnacknowledged() throws Exception {
        confirmBehavior = correlationData -> {
        };

        assertThatThrownBy(() -> support.handleTransientFailure(NotificationChannel.INAPP,
                message(0), amqpChannel, event(NOW), new IllegalStateException("boom")))
                .isInstanceOfSatisfying(RetryTransferFailedException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(RetryTransferFailedException.ReasonCode.PUBLISH_TIMEOUT));
        verify(amqpChannel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void interruptedConfirmRestoresTheInterruptFlagAndLeavesTheMessageUnacknowledged()
            throws Exception {
        confirmBehavior = correlationData -> {
        };

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> support.handleTransientFailure(NotificationChannel.INAPP,
                    message(0), amqpChannel, event(NOW), new IllegalStateException("boom")))
                    .isInstanceOfSatisfying(RetryTransferFailedException.class, failure ->
                            assertThat(failure.code()).isEqualTo(
                                    RetryTransferFailedException.ReasonCode.BROKER_UNAVAILABLE));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(amqpChannel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void brokerFailureIsBoundedAndLeavesTheMessageUnacknowledged() throws Exception {
        doThrow(new AmqpException("broker down at localhost"))
                .when(operations).send(anyString(), anyString(), any(Message.class),
                        any(CorrelationData.class));

        assertThatThrownBy(() -> support.handleTransientFailure(NotificationChannel.INAPP,
                message(0), amqpChannel, event(NOW), new IllegalStateException("boom")))
                .isInstanceOfSatisfying(RetryTransferFailedException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(
                            RetryTransferFailedException.ReasonCode.BROKER_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("broker down", "localhost");
                    assertThat(failure.getCause()).isNull();
                });
        verify(amqpChannel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void retryForwardingPreservesIdentityAndEventHeaders() throws Exception {
        Message original = message(1);

        support.handleTransientFailure(NotificationChannel.INAPP, original, amqpChannel,
                event(NOW), new IllegalStateException("boom"));

        Message forwarded = sent.getFirst().message();
        assertThat(forwarded).isNotSameAs(original);
        assertThat(forwarded.getBody()).isEqualTo(original.getBody());
        assertThat(forwarded.getMessageProperties().getMessageId()).isEqualTo("evt-123");
        assertThat(forwarded.getMessageProperties().getTimestamp())
                .isEqualTo(original.getMessageProperties().getTimestamp());
        assertThat(forwarded.getMessageProperties().getCorrelationId()).isEqualTo("corr-123");
        assertThat(forwarded.getMessageProperties().getContentType())
                .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(forwarded.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(forwarded.getMessageProperties().<Integer>getHeader("x-schema-version"))
                .isEqualTo(1);
        assertThat(forwarded.getMessageProperties().<String>getHeader("x-event-type"))
                .isEqualTo(OrderEventType.ORDER_PLACED.name());
        assertThat(forwarded.getMessageProperties().<Long>getHeader("x-order-sequence"))
                .isEqualTo(5L);
        assertThat(forwarded.getMessageProperties().<String>getHeader("x-entry-point"))
                .isEqualTo("CHECKOUT_WEB");
        assertThat(retryAttempt(forwarded)).isEqualTo(2);
        assertThat(retryAttempt(original)).isEqualTo(1);
    }

    @Test
    void deadLetterForwardingPreservesIdentityAndAddsTheQuarantineReason() throws Exception {
        Message original = message(2);

        support.handlePermanentFailure(NotificationChannel.EMAIL, original, amqpChannel,
                event(NOW), "RETRIES_EXHAUSTED");

        Message forwarded = sent.getFirst().message();
        assertThat(forwarded.getMessageProperties().getMessageId()).isEqualTo("evt-123");
        assertThat(forwarded.getMessageProperties().getTimestamp())
                .isEqualTo(original.getMessageProperties().getTimestamp());
        assertThat(forwarded.getMessageProperties().getCorrelationId()).isEqualTo("corr-123");
        assertThat(forwarded.getMessageProperties().<String>getHeader("x-event-type"))
                .isEqualTo(OrderEventType.ORDER_PLACED.name());
        assertThat(retryAttempt(forwarded)).isEqualTo(2);
        assertThat(reason(forwarded)).isEqualTo("RETRIES_EXHAUSTED");
    }

    private void assertRetry(int attempt, String expectedQueue, int expectedHeader)
            throws Exception {
        support.handleTransientFailure(NotificationChannel.INAPP, message(attempt),
                amqpChannel, event(NOW), new IllegalStateException("boom"));

        assertThat(sent).singleElement().satisfies(published -> {
            assertThat(published.exchange()).isEqualTo(RabbitTopology.RETRY_EXCHANGE);
            assertThat(published.routingKey()).isEqualTo(expectedQueue);
            assertThat(retryAttempt(published.message())).isEqualTo(expectedHeader);
        });

        InOrder order = inOrder(operations, amqpChannel);
        order.verify(operations).send(eq(RabbitTopology.RETRY_EXCHANGE), eq(expectedQueue),
                any(Message.class), any(CorrelationData.class));
        order.verify(amqpChannel).basicAck(DELIVERY_TAG, false);
    }

    private static DatabaseClock clockAt(Instant now) {
        DatabaseClock clock = mock(DatabaseClock.class);
        when(clock.now()).thenReturn(now);
        return clock;
    }

    private static OrderEvent event(Instant occurredAt) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_" + UUID.randomUUID(), 1L,
                occurredAt, 42L, OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                new BigDecimal("199.90"), null, "corr-123", "CHECKOUT_WEB");
    }

    private static Message message(int attempt) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        properties.setMessageId("evt-123");
        properties.setTimestamp(Date.from(NOW));
        properties.setCorrelationId("corr-123");
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("x-schema-version", 1);
        properties.setHeader("x-event-type", OrderEventType.ORDER_PLACED.name());
        properties.setHeader("x-order-sequence", 5L);
        properties.setHeader("x-entry-point", "CHECKOUT_WEB");
        if (attempt >= 0) {
            properties.setHeader(RabbitRetryPublisher.RETRY_ATTEMPT_HEADER, attempt);
        }
        return new Message(BODY, properties);
    }

    private static int retryAttempt(Message message) {
        return message.getMessageProperties().<Integer>getHeader(
                RabbitRetryPublisher.RETRY_ATTEMPT_HEADER);
    }

    private static String reason(Message message) {
        return message.getMessageProperties().getHeader(
                RabbitRetryPublisher.QUARANTINE_REASON_HEADER);
    }

    private record Sent(String exchange, String routingKey, Message message) {
    }
}
