package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.messaging.rabbit.NotificationRabbitProperties;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notifications.delivery-enabled", havingValue = "true")
public class RabbitRetryPublisher {

    public static final String RETRY_ATTEMPT_HEADER = "x-retry-attempt";
    public static final String QUARANTINE_REASON_HEADER = "x-quarantine-reason";

    private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;
    private final NotificationRabbitProperties properties;

    public void publishRetry(NotificationChannel channel, Message original, Duration delay,
                             int attempt) {
        Message forwarded = forward(original, copy ->
                copy.setHeader(RETRY_ATTEMPT_HEADER, attempt));
        publish(RabbitTopology.RETRY_EXCHANGE, channel.retryQueue(delay), forwarded);
    }

    public void publishDeadLetter(NotificationChannel channel, Message original, String reasonCode) {
        Object attempt = original.getMessageProperties().getHeader(RETRY_ATTEMPT_HEADER);
        Message forwarded = forward(original, copy -> {
            if (attempt instanceof Number) {
                copy.setHeader(RETRY_ATTEMPT_HEADER, attempt);
            } else {
                copy.getHeaders().remove(RETRY_ATTEMPT_HEADER);
            }
            copy.setHeader(QUARANTINE_REASON_HEADER, reasonCode);
        });
        publish(RabbitTopology.DLX, channel.dlq(), forwarded);
    }

    private void publish(String exchange, String routingKey, Message message) {
        CorrelationData correlationData = new CorrelationData();
        Duration confirmTimeout = properties.getConfirmTimeout() != null
                ? properties.getConfirmTimeout()
                : DEFAULT_CONFIRM_TIMEOUT;
        try {
            rabbitTemplate.invoke(operations -> {
                operations.send(exchange, routingKey, message, correlationData);
                awaitConfirmed(correlationData, confirmTimeout);
                return null;
            });
        } catch (AmqpException e) {
            throw new RetryTransferFailedException(
                    RetryTransferFailedException.ReasonCode.BROKER_UNAVAILABLE);
        }
    }

    private static void awaitConfirmed(CorrelationData correlationData, Duration confirmTimeout) {
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (correlationData.getReturned() != null) {
                throw new RetryTransferFailedException(
                        RetryTransferFailedException.ReasonCode.PUBLISH_UNROUTABLE);
            }
            if (!confirm.ack()) {
                throw new RetryTransferFailedException(
                        RetryTransferFailedException.ReasonCode.PUBLISH_NACK);
            }
        } catch (TimeoutException e) {
            throw new RetryTransferFailedException(
                    RetryTransferFailedException.ReasonCode.PUBLISH_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryTransferFailedException(
                    RetryTransferFailedException.ReasonCode.BROKER_UNAVAILABLE);
        } catch (ExecutionException | CancellationException e) {
            throw new RetryTransferFailedException(
                    RetryTransferFailedException.ReasonCode.BROKER_UNAVAILABLE);
        }
    }

    private static Message forward(Message original, Consumer<MessageProperties> customizer) {
        MessageProperties copy = MessagePropertiesBuilder
                .fromClonedProperties(original.getMessageProperties())
                .build();
        customizer.accept(copy);
        return new Message(original.getBody(), copy);
    }
}
