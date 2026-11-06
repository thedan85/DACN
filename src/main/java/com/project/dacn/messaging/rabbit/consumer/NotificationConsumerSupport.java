package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notifications.delivery-enabled", havingValue = "true")
public class NotificationConsumerSupport {

    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(120));

    private final RabbitRetryPublisher retryPublisher;
    private final OrderEventAgeGuard ageGuard;

    public boolean rejectIfOutsideWindow(
            NotificationChannel channel, Message message,
            com.rabbitmq.client.Channel amqpChannel, OrderEvent event) throws IOException {
        OrderEventAgeGuard.AgeDecision decision = ageGuard.evaluate(event);
        if (decision == OrderEventAgeGuard.AgeDecision.OK) {
            return false;
        }
        handlePermanentFailure(channel, message, amqpChannel, event,
                decision == OrderEventAgeGuard.AgeDecision.TOO_OLD
                        ? "EVENT_TOO_OLD"
                        : "EVENT_FUTURE");
        return true;
    }

    public void handleTransientFailure(
            NotificationChannel channel, Message message,
            com.rabbitmq.client.Channel amqpChannel, OrderEvent event, Throwable failure)
            throws IOException {
        int attempt = retryAttempt(message);
        if (attempt >= RETRY_DELAYS.size()) {
            handlePermanentFailure(channel, message, amqpChannel, event, "RETRIES_EXHAUSTED");
            return;
        }
        retryPublisher.publishRetry(channel, message, RETRY_DELAYS.get(attempt), attempt + 1);
        amqpChannel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    public void handlePermanentFailure(
            NotificationChannel channel, Message message,
            com.rabbitmq.client.Channel amqpChannel, OrderEvent event, String reasonCode)
            throws IOException {
        // The event is null when decoding failed; only the original message is forwarded.
        retryPublisher.publishDeadLetter(channel, message, reasonCode);
        amqpChannel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private static int retryAttempt(Message message) {
        Object value = message.getMessageProperties()
                .getHeader(RabbitRetryPublisher.RETRY_ATTEMPT_HEADER);
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }
}
