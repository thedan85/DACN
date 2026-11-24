package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.messaging.outbox.OrderEventCodecException;
import com.project.dacn.service.engagement.NotificationFanoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InAppNotificationConsumerTest {

    private static final long DELIVERY_TAG = 11L;

    private OrderEventCodec codec;
    private NotificationConsumerSupport support;
    private NotificationFanoutService fanout;
    private com.rabbitmq.client.Channel channel;
    private InAppNotificationConsumer consumer;
    private OrderEvent event;

    @BeforeEach
    void setUp() {
        codec = mock(OrderEventCodec.class);
        support = mock(NotificationConsumerSupport.class);
        fanout = mock(NotificationFanoutService.class);
        channel = mock(com.rabbitmq.client.Channel.class);
        consumer = new InAppNotificationConsumer(codec, support, fanout);
        event = event();
    }

    @Test
    void decodeFailureQuarantinesOriginalMessageWithRawIdentityAndNeverCallsFanout() throws Exception {
        doThrow(new OrderEventCodecException(OrderEventCodecException.ReasonCode.EVENT_INVALID))
                .when(codec).decode(any());
        Message message = message(DELIVERY_TAG);

        consumer.receive(message, channel);

        verify(support).handlePermanentFailure(NotificationChannel.INAPP, message, channel,
                null, "EVENT_INVALID");
        verify(fanout, never()).handle(any());
        verify(support, never()).rejectIfOutsideWindow(any(), any(), any(), any());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void unsupportedSchemaFailureUsesTheSamePermanentQuarantine() throws Exception {
        doThrow(new OrderEventCodecException(OrderEventCodecException.ReasonCode.EVENT_UNSUPPORTED))
                .when(codec).decode(any());
        Message message = message(DELIVERY_TAG);

        consumer.receive(message, channel);

        verify(support).handlePermanentFailure(NotificationChannel.INAPP, message, channel,
                null, "EVENT_INVALID");
    }

    @Test
    void ageRejectionHappensBeforeAnyRecipientWork() throws Exception {
        when(codec.decode(any())).thenReturn(event);
        when(support.rejectIfOutsideWindow(eq(NotificationChannel.INAPP), any(Message.class),
                eq(channel), eq(event))).thenReturn(true);
        Message message = message(DELIVERY_TAG);

        consumer.receive(message, channel);

        InOrder order = inOrder(support, fanout, channel);
        order.verify(support).rejectIfOutsideWindow(NotificationChannel.INAPP, message,
                channel, event);
        order.verify(fanout, never()).handle(any());
        order.verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(fanout, never()).handle(any());
    }

    @Test
    void commitsHandlerBeforeAcknowledging() throws Exception {
        when(codec.decode(any())).thenReturn(event);
        when(support.rejectIfOutsideWindow(any(), any(), any(), any())).thenReturn(false);
        Message message = message(DELIVERY_TAG);

        consumer.receive(message, channel);

        InOrder order = inOrder(fanout, channel);
        order.verify(fanout).handle(event);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
        verify(support, never()).handleTransientFailure(any(), any(), any(), any(), any());
    }

    @Test
    void handlerFailureUsesConfirmedRetryTransferAndDoesNotDirectlyAck() throws Exception {
        when(codec.decode(any())).thenReturn(event);
        when(support.rejectIfOutsideWindow(any(), any(), any(), any())).thenReturn(false);
        RuntimeException failure = new IllegalStateException("tx failed");
        doThrow(failure).when(fanout).handle(event);
        Message message = message(DELIVERY_TAG);

        consumer.receive(message, channel);

        verify(support).handleTransientFailure(NotificationChannel.INAPP, message, channel,
                event, failure);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void exhaustedTransientTransferUsesConfirmedDlqWithoutDirectAck() throws Exception {
        when(codec.decode(any())).thenReturn(event);
        when(support.rejectIfOutsideWindow(any(), any(), any(), any())).thenReturn(false);
        doThrow(new IllegalStateException("tx failed")).when(fanout).handle(event);
        Message message = message(DELIVERY_TAG);

        consumer.receive(message, channel);

        // The support component maps attempt>=3 to handlePermanentFailure internally;
        // the consumer only delegates and never acknowledges on a transfer path.
        verify(support).handleTransientFailure(eq(NotificationChannel.INAPP), eq(message),
                eq(channel), eq(event), any());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void unavailableTransferPropagatesAndLeavesMessageUnacknowledged() throws Exception {
        when(codec.decode(any())).thenReturn(event);
        when(support.rejectIfOutsideWindow(any(), any(), any(), any())).thenReturn(false);
        doThrow(new IllegalStateException("tx failed")).when(fanout).handle(event);
        doThrow(new RetryTransferFailedException(RetryTransferFailedException.ReasonCode.BROKER_UNAVAILABLE))
                .when(support).handleTransientFailure(any(), any(), any(), any(), any());
        Message message = message(DELIVERY_TAG);

        assertThatThrownBy(() -> consumer.receive(message, channel))
                .isInstanceOf(RetryTransferFailedException.class);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void duplicateDeliveriesEachCommitThenAckIndependently() throws Exception {
        when(codec.decode(any())).thenReturn(event);
        when(support.rejectIfOutsideWindow(any(), any(), any(), any())).thenReturn(false);
        Message first = message(DELIVERY_TAG);
        Message second = message(DELIVERY_TAG + 1);

        consumer.receive(first, channel);
        consumer.receive(second, channel);

        verify(fanout, org.mockito.Mockito.times(2)).handle(event);
        InOrder order = inOrder(channel);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
        order.verify(channel).basicAck(DELIVERY_TAG + 1, false);
    }

    @Test
    void listenerHasStableIdAndBoundQueue() throws Exception {
        RabbitListener annotation = InAppNotificationConsumer.class
                .getMethod("receive", Message.class, com.rabbitmq.client.Channel.class)
                .getAnnotation(RabbitListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.id()).isEqualTo("order-notifications-inapp");
        assertThat(annotation.queues()).containsExactly("dacn.notifications.inapp");
    }

    private static OrderEvent event() {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_t11_" + UUID.randomUUID(), 1L,
                Instant.now().minusSeconds(30), 42L, OrderStatus.PENDING, PaymentStatus.PENDING,
                PaymentMethod.COD, new BigDecimal("19.90"), null, "corr", "TEST");
    }

    private static Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setMessageId(UUID.randomUUID().toString());
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }
}
