package com.project.dacn.messaging.rabbit;

import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.EventPublishException;
import com.project.dacn.messaging.OrderEventPublisher;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notifications.delivery-enabled", havingValue = "true")
public class RabbitEventPublisher implements OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final OrderEventCodec codec;
    private final NotificationRabbitProperties properties;

    @Override
    public void publish(OrderEvent event) throws EventPublishException {
        Message message = MessageBuilder.withBody(codec.encode(event))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.eventId().toString())
                .setTimestamp(Date.from(event.occurredAt()))
                .setCorrelationId(event.correlationId())
                .setHeader("x-schema-version", event.schemaVersion())
                .setHeader("x-event-type", event.eventType().name())
                .setHeader("x-order-sequence", event.orderSequence())
                .setHeader("x-entry-point", event.entryPoint())
                .build();

        CorrelationData correlationData = new CorrelationData(event.eventId().toString());
        Duration confirmTimeout = properties.getConfirmTimeout();

        try {
            rabbitTemplate.invoke(operations -> {
                operations.convertAndSend(RabbitTopology.EXCHANGE,
                        event.eventType().routingKey(), message, correlationData);
                awaitConfirmed(correlationData, confirmTimeout);
                return null;
            });
        } catch (AmqpException e) {
            throw new EventPublishException(EventPublishException.ReasonCode.BROKER_UNAVAILABLE);
        }
    }

    private static void awaitConfirmed(CorrelationData correlationData, Duration confirmTimeout) {
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (correlationData.getReturned() != null) {
                throw new EventPublishException(EventPublishException.ReasonCode.PUBLISH_UNROUTABLE);
            }
            if (!confirm.ack()) {
                throw new EventPublishException(EventPublishException.ReasonCode.PUBLISH_NACK);
            }
        } catch (TimeoutException e) {
            throw new EventPublishException(EventPublishException.ReasonCode.PUBLISH_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(EventPublishException.ReasonCode.BROKER_UNAVAILABLE);
        } catch (ExecutionException | CancellationException e) {
            throw new EventPublishException(EventPublishException.ReasonCode.BROKER_UNAVAILABLE);
        }
    }
}
