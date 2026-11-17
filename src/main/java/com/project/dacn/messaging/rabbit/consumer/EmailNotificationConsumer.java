package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.entity.engagement.EmailDeliveryState;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.messaging.outbox.OrderEventCodecException;
import com.project.dacn.service.engagement.EmailDeliveryService;
import com.project.dacn.service.engagement.EmailDeliveryService.Outcome;
import com.project.dacn.service.engagement.OrderEmailService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.notifications.delivery-enabled", havingValue = "true")
public class EmailNotificationConsumer {

    public static final String LISTENER_ID = "order-notifications-email";

    private final OrderEventCodec codec;
    private final NotificationConsumerSupport support;
    private final OrderEmailService emailService;
    private final EmailDeliveryService delivery;
    private final EmailTransportPause pause;
    private final RabbitListenerEndpointRegistry listeners;

    public EmailNotificationConsumer(OrderEventCodec codec,
                                     NotificationConsumerSupport support,
                                     OrderEmailService emailService,
                                     EmailDeliveryService delivery,
                                     EmailTransportPause pause,
                                     RabbitListenerEndpointRegistry listeners) {
        this.codec = codec;
        this.support = support;
        this.emailService = emailService;
        this.delivery = delivery;
        this.pause = pause;
        this.listeners = listeners;
    }

    @RabbitListener(id = LISTENER_ID, queues = RabbitTopology.EMAIL_QUEUE)
    public void receive(Message message, com.rabbitmq.client.Channel channel) throws IOException {
        OrderEvent event;
        try {
            event = codec.decode(message.getBody());
        } catch (OrderEventCodecException failure) {
            support.handlePermanentFailure(NotificationChannel.EMAIL, message, channel,
                    null, "EVENT_INVALID");
            return;
        }

        if (support.rejectIfOutsideWindow(NotificationChannel.EMAIL, message, channel, event)) {
            return;
        }

        // Unsafe transport termination: only this listener stops. No replacement SMTP.
        if (pause.isPaused()) {
            support.handlePermanentFailure(NotificationChannel.EMAIL, message, channel,
                    event, "EMAIL_PAUSED");
            return;
        }

        long recipientId = Long.parseLong(event.customerUserId());
        try {
            // Void and durable; the ledger row is the authority for the outcome.
            emailService.process(event);
        } catch (RuntimeException failure) {
            // Outcome persistence may have failed after SMTP acceptance. The work is
            // unresolved: transfer durably, never ACK it away or hot-requeue.
            support.handleTransientFailure(NotificationChannel.EMAIL, message, channel,
                    event, failure);
            return;
        }

        Outcome outcome = delivery.outcome(event.eventId(), recipientId);
        if (outcome == null) {
            // No email work for this event (channel policy never sends mail).
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            return;
        }

        switch (outcome.state()) {
            case SENT, SKIPPED -> channel.basicAck(
                    message.getMessageProperties().getDeliveryTag(), false);
            case UNKNOWN, FAILED -> quarantine(message, channel, event, outcome);
            case PENDING, IN_FLIGHT -> support.handleTransientFailure(
                    NotificationChannel.EMAIL, message, channel, event,
                    new IllegalStateException("EMAIL_WORK_UNRESOLVED"));
        }
    }

    private void quarantine(Message message, com.rabbitmq.client.Channel channel,
                            OrderEvent event, Outcome outcome) throws IOException {
        boolean unsafe = outcome.state() == EmailDeliveryState.UNKNOWN;
        if (unsafe) {
            // Acceptance was possible but unobserved, or termination was not proved.
            // Stop unsafe sending on this listener only; never touch in-app.
            pause.pause();
            MessageListenerContainer emailListener = listeners.getListenerContainer(LISTENER_ID);
            if (emailListener != null) {
                emailListener.stop();
            }
        }
        String reason = unsafe ? "EMAIL_UNKNOWN" : "EMAIL_FAILED";
        String code = outcome.failureCode() == null ? "UNSPECIFIED" : outcome.failureCode();
        support.handlePermanentFailure(NotificationChannel.EMAIL, message, channel, event,
                reason, Map.of("x-delivery-id", outcome.deliveryId(), "x-safe-code", code));
    }
}
