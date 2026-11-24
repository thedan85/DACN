package com.project.dacn.messaging.rabbit;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.notifications.delivery-enabled", havingValue = "true")
public class RabbitTopologyConfig {

    static final int DELIVERY_LIMIT = 4;

    private static final int[] RETRY_TTLS_MS = {10_000, 30_000, 120_000};

    @Bean
    public RabbitDeliveryStartup rabbitDeliveryStartup(AmqpAdmin admin,
                                                       RabbitTopologyVerifier verifier,
                                                       RabbitListenerEndpointRegistry listeners) {
        return new RabbitDeliveryStartup(admin, verifier, listeners);
    }

    @Bean
    public Declarables notificationTopology() {
        TopicExchange exchange = new TopicExchange(RabbitTopology.EXCHANGE, true, false);
        DirectExchange retryExchange = new DirectExchange(RabbitTopology.RETRY_EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(RabbitTopology.DLX, true, false);

        List<Declarable> declarations = new ArrayList<>();
        declarations.add(exchange);
        declarations.add(retryExchange);
        declarations.add(dlx);
        declarations.addAll(queueDeclarations(exchange, retryExchange, dlx));
        return new Declarables(declarations);
    }

    private static List<Declarable> queueDeclarations(TopicExchange exchange,
                                                      DirectExchange retryExchange,
                                                      DirectExchange dlx) {
        List<Declarable> declarations = new ArrayList<>();
        for (String mainQueueName : List.of(RabbitTopology.INAPP_QUEUE, RabbitTopology.EMAIL_QUEUE)) {
            Queue mainQueue = mainQueue(mainQueueName);
            declarations.add(mainQueue);
            declarations.add(BindingBuilder.bind(mainQueue)
                    .to(exchange).with(RabbitTopology.ROUTING_PATTERN));
            declarations.add(BindingBuilder.bind(mainQueue)
                    .to(retryExchange).with(mainQueueName));

            for (int ttlMs : RETRY_TTLS_MS) {
                Queue retryQueue = retryQueue(mainQueueName, ttlMs);
                declarations.add(retryQueue);
                declarations.add(BindingBuilder.bind(retryQueue)
                        .to(retryExchange).with(retryQueue.getName()));
            }

            Queue deadLetterQueue = deadLetterQueue(mainQueueName);
            declarations.add(deadLetterQueue);
            declarations.add(BindingBuilder.bind(deadLetterQueue)
                    .to(dlx).with(deadLetterQueue.getName()));
        }
        return declarations;
    }

    private static Queue mainQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish")
                .withArgument("x-dead-letter-exchange", RabbitTopology.DLX)
                .withArgument("x-dead-letter-routing-key", name + ".dlq")
                .withArgument("x-delivery-limit", DELIVERY_LIMIT)
                .build();
    }

    private static Queue retryQueue(String mainQueueName, int ttlMs) {
        return QueueBuilder.durable(mainQueueName + ".retry." + (ttlMs / 1000) + "s")
                .quorum()
                .withArgument("x-message-ttl", ttlMs)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish")
                .withArgument("x-dead-letter-exchange", RabbitTopology.RETRY_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", mainQueueName)
                .build();
    }

    private static Queue deadLetterQueue(String mainQueueName) {
        return QueueBuilder.durable(mainQueueName + ".dlq")
                .quorum()
                .build();
    }
}
