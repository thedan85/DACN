package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.entity.engagement.EmailDeliveryState;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.messaging.outbox.OrderEventCodecException;
import com.project.dacn.service.engagement.EmailDeliveryService;
import com.project.dacn.service.engagement.EmailDeliveryService.Outcome;
import com.project.dacn.service.engagement.OrderEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailNotificationConsumerTest {

    private static final long DELIVERY_TAG = 21L;
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private OrderEventCodec codec;
    private RabbitRetryPublisher retryPublisher;
    private NotificationConsumerSupport support;
    private OrderEmailService emailService;
    private EmailDeliveryService delivery;
    private EmailTransportPause pause;
    private RabbitListenerEndpointRegistry listeners;
    private MessageListenerContainer emailContainer;
    private com.rabbitmq.client.Channel channel;
    private EmailNotificationConsumer consumer;
    private OrderEvent event;

    @BeforeEach
    void setUp() {
        codec = mock(OrderEventCodec.class);
        retryPublisher = mock(RabbitRetryPublisher.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        when(clock.now()).thenReturn(NOW);
        support = new NotificationConsumerSupport(retryPublisher, new OrderEventAgeGuard(clock));
        emailService = mock(OrderEmailService.class);
        delivery = mock(EmailDeliveryService.class);
        pause = new EmailTransportPause();
        listeners = mock(RabbitListenerEndpointRegistry.class);
        emailContainer = mock(MessageListenerContainer.class);
        when(listeners.getListenerContainer(EmailNotificationConsumer.LISTENER_ID))
                .thenReturn(emailContainer);
        channel = mock(com.rabbitmq.client.Channel.class);
        consumer = new EmailNotificationConsumer(codec, support, emailService, delivery,
                pause, listeners);
        event = event();
        when(codec.decode(any())).thenReturn(event);
        when(delivery.outcome(event.eventId(), recipientId())).thenReturn(outcome(
                EmailDeliveryState.SENT, "SMTP_ACCEPTED"));
    }

    @Test
    void decodeFailureQuarantinesOriginalMessageAndNeverStartsSmtp() throws Exception {
        doThrow(new OrderEventCodecException(OrderEventCodecException.ReasonCode.EVENT_INVALID))
                .when(codec).decode(any());
        Message message = message();

        consumer.receive(message, channel);

        verify(retryPublisher).publishDeadLetter(NotificationChannel.EMAIL, message,
                "EVENT_INVALID", Map.of());
        verifyNoInteractions(emailService);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void ageRejectionHappensBeforeAnyEmailWork() throws Exception {
        OrderEvent old = OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_t11_old", 1L,
                NOW.minus(31, java.time.temporal.ChronoUnit.DAYS), recipientId(),
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                new BigDecimal("10.00"), null, "corr", "TEST");
        when(codec.decode(any())).thenReturn(old);
        Message message = message();

        consumer.receive(message, channel);

        verify(retryPublisher).publishDeadLetter(eq(NotificationChannel.EMAIL), eq(message),
                eq("EVENT_TOO_OLD"), anyMap());
        verifyNoInteractions(emailService);
        verify(delivery, never()).outcome(any(), anyLong());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void sentOutcomeAcknowledgesOnlyAfterTheDurableOutcome() throws Exception {
        Message message = message();

        consumer.receive(message, channel);

        InOrder order = inOrder(emailService, delivery, channel);
        order.verify(emailService).process(event);
        order.verify(delivery).outcome(event.eventId(), recipientId());
        order.verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(retryPublisher);
    }

    @Test
    void skippedOutcomeAcknowledges() throws Exception {
        when(delivery.outcome(event.eventId(), recipientId())).thenReturn(outcome(
                EmailDeliveryState.SKIPPED, "POLICY_SKIPPED"));

        consumer.receive(message(), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(retryPublisher);
    }

    @Test
    void noLedgerRowAcknowledgesAsNothingToDo() throws Exception {
        when(delivery.outcome(event.eventId(), recipientId())).thenReturn(null);

        consumer.receive(message(), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(retryPublisher);
    }

    @Test
    void pendingOutcomeUsesConfirmedRetryTransferAndNoDirectAck() throws Exception {
        when(delivery.outcome(event.eventId(), recipientId())).thenReturn(
                new Outcome(EmailDeliveryState.PENDING, 5L, "SMTP_PRE_ACCEPTANCE",
                        NOW.plusSeconds(10)));
        Message message = message();

        consumer.receive(message, channel);

        InOrder order = inOrder(retryPublisher, channel);
        order.verify(retryPublisher).publishRetry(NotificationChannel.EMAIL, message,
                java.time.Duration.ofSeconds(10), 1);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, org.mockito.Mockito.times(1)).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void busyInFlightOutcomeUsesConfirmedRetryNotAHarmlessDuplicateAck() throws Exception {
        when(delivery.outcome(event.eventId(), recipientId())).thenReturn(outcome(
                EmailDeliveryState.IN_FLIGHT, "IN_FLIGHT"));
        Message message = message();

        consumer.receive(message, channel);

        InOrder order = inOrder(retryPublisher, channel);
        order.verify(retryPublisher).publishRetry(NotificationChannel.EMAIL, message,
                java.time.Duration.ofSeconds(10), 1);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void unknownOutcomeQuarantinesWithDeliveryIdsAndPausesOnlyEmail() throws Exception {
        when(delivery.outcome(event.eventId(), recipientId())).thenReturn(outcome(
                EmailDeliveryState.UNKNOWN, "SMTP_ACCEPTANCE_AMBIGUOUS"));
        Message message = message();

        consumer.receive(message, channel);

        InOrder order = inOrder(retryPublisher, channel);
        order.verify(retryPublisher).publishDeadLetter(eq(NotificationChannel.EMAIL), eq(message),
                eq("EMAIL_UNKNOWN"), eq(Map.of("x-delivery-id", 5L,
                        "x-safe-code", "SMTP_ACCEPTANCE_AMBIGUOUS")));
        order.verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(pause.isPaused()).isTrue();
        verify(listeners).getListenerContainer("order-notifications-email");
        verify(emailContainer).stop();
        verify(listeners, never()).getListenerContainer("order-notifications-inapp");
    }

    @Test
    void failedOutcomeQuarantinesToEmailDlq() throws Exception {
        when(delivery.outcome(event.eventId(), recipientId())).thenReturn(outcome(
                EmailDeliveryState.FAILED, "SMTP_PRE_ACCEPTANCE"));

        consumer.receive(message(), channel);

        verify(retryPublisher).publishDeadLetter(eq(NotificationChannel.EMAIL), any(Message.class),
                eq("EMAIL_FAILED"), anyMap());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(pause.isPaused()).isFalse();
    }

    @Test
    void unavailableQuarantineTransferDoesNotAckButStillPausesEmail() throws Exception {
        when(delivery.outcome(event.eventId(), recipientId())).thenReturn(outcome(
                EmailDeliveryState.UNKNOWN, "SMTP_ACCEPTANCE_AMBIGUOUS"));
        doThrow(new RetryTransferFailedException(
                RetryTransferFailedException.ReasonCode.BROKER_UNAVAILABLE))
                .when(retryPublisher).publishDeadLetter(any(), any(), anyString(), anyMap());

        assertThatThrownBy(() -> consumer.receive(message(), channel))
                .isInstanceOf(RetryTransferFailedException.class);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(pause.isPaused()).isTrue();
        verify(emailContainer).stop();
    }

    @Test
    void unavailableRetryTransferDoesNotAck() throws Exception {
        when(delivery.outcome(event.eventId(), recipientId())).thenReturn(outcome(
                EmailDeliveryState.PENDING, "SMTP_PRE_ACCEPTANCE"));
        doThrow(new RetryTransferFailedException(RetryTransferFailedException.ReasonCode.PUBLISH_NACK))
                .when(retryPublisher).publishRetry(any(), any(), any(), anyInt());

        assertThatThrownBy(() -> consumer.receive(message(), channel))
                .isInstanceOf(RetryTransferFailedException.class);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void processFailureUsesConfirmedRetryAndNeverDirectAck() throws Exception {
        RuntimeException failure = new IllegalStateException("ledger write failed");
        doThrow(failure).when(emailService).process(event);
        Message message = message();

        consumer.receive(message, channel);

        InOrder order = inOrder(retryPublisher, channel);
        order.verify(retryPublisher).publishRetry(NotificationChannel.EMAIL, message,
                java.time.Duration.ofSeconds(10), 1);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void pausedConsumerNeverStartsSmtpAndQuarantines() throws Exception {
        pause.pause();
        Message message = message();

        consumer.receive(message, channel);

        verifyNoInteractions(emailService);
        verify(retryPublisher).publishDeadLetter(eq(NotificationChannel.EMAIL), eq(message),
                eq("EMAIL_PAUSED"), anyMap());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void listenerHasStableIdAndBoundQueue() throws Exception {
        RabbitListener annotation = EmailNotificationConsumer.class
                .getMethod("receive", Message.class, com.rabbitmq.client.Channel.class)
                .getAnnotation(RabbitListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.id()).isEqualTo("order-notifications-email");
        assertThat(annotation.queues()).containsExactly("dacn.notifications.email");
    }

    private static long recipientId() {
        return 42L;
    }

    private static Outcome outcome(EmailDeliveryState state, String code) {
        return new Outcome(state, 5L, code, null);
    }

    private static OrderEvent event() {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_t11_email", 1L,
                NOW.minusSeconds(30), recipientId(), OrderStatus.PENDING, PaymentStatus.PENDING,
                PaymentMethod.COD, new BigDecimal("19.90"), null, "corr", "TEST");
    }

    private static Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        properties.setMessageId(UUID.randomUUID().toString());
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }
}
