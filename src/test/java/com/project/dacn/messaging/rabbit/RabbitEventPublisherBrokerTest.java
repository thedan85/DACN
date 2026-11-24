package com.project.dacn.messaging.rabbit;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.EventPublishException;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.support.ExternalRabbit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("external-rabbit-dacn-topology")
class RabbitEventPublisherBrokerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-03T10:15:30Z");

    private static CachingConnectionFactory connectionFactory;
    private static RabbitTemplate template;
    private static RabbitAdmin admin;
    private static Collection<Declarable> declarations;
    private static ExternalRabbit.OwnedTopology ownedTopology;
    private static OrderEventCodec codec;
    private static RabbitEventPublisher publisher;

    @BeforeAll
    static void setUp() {
        connectionFactory = ExternalRabbit.fromEnvironment().connectionFactory();
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        admin = new RabbitAdmin(connectionFactory);
        declarations = new RabbitTopologyConfig().notificationTopology().getDeclarables();
        ownedTopology = ExternalRabbit.declare(admin, declarations);

        codec = new OrderEventCodec(JsonMapper.builder().build());
        NotificationRabbitProperties properties = new NotificationRabbitProperties();
        properties.setConfirmTimeout(Duration.ofSeconds(5));
        publisher = new RabbitEventPublisher(template, codec, properties);
    }

    @BeforeEach
    void purgeQueues() {
        ownedTopology.purgeQueues();
    }

    @AfterAll
    static void tearDown() {
        if (ownedTopology != null) {
            ownedTopology.close();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void publishesAPersistentJsonMessageToBothMainQueues() {
        OrderEvent event = sampleEvent();
        byte[] payload = codec.encode(event);

        publisher.publish(event);

        Message inapp = template.receive(RabbitTopology.INAPP_QUEUE, 5_000);
        Message email = template.receive(RabbitTopology.EMAIL_QUEUE, 5_000);

        assertThat(inapp).isNotNull();
        assertThat(email).isNotNull();
        assertThat(inapp.getBody()).isEqualTo(payload);
        assertThat(email.getBody()).isEqualTo(payload);

        for (Message message : List.of(inapp, email)) {
            MessageProperties properties = message.getMessageProperties();
            assertThat(properties.getContentType())
                    .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            assertThat(properties.getContentEncoding()).isEqualTo("UTF-8");
            assertThat(properties.getReceivedDeliveryMode())
                    .isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(properties.getMessageId()).isEqualTo(event.eventId().toString());
            assertThat(properties.getTimestamp().toInstant()).isEqualTo(OCCURRED_AT);
            assertThat(properties.getCorrelationId()).isEqualTo(event.correlationId());
            assertThat(properties.<Integer>getHeader("x-schema-version"))
                    .isEqualTo(event.schemaVersion());
            assertThat(properties.<String>getHeader("x-event-type"))
                    .isEqualTo(event.eventType().name());
            assertThat(properties.<Long>getHeader("x-order-sequence"))
                    .isEqualTo(event.orderSequence());
            assertThat(properties.<String>getHeader("x-entry-point"))
                    .isEqualTo(event.entryPoint());
            assertThat(properties.getReceivedRoutingKey())
                    .isEqualTo(event.eventType().routingKey());
        }
    }

    @Test
    void mandatoryReturnMakesAnUnroutablePublishFailWithoutDeliveringAMessage() {
        removeTopicBindings();
        try {
            assertThatThrownBy(() -> publisher.publish(sampleEvent()))
                    .isInstanceOfSatisfying(EventPublishException.class, exception ->
                            assertThat(exception.code())
                                    .isEqualTo(EventPublishException.ReasonCode.PUBLISH_UNROUTABLE));

            assertThat(template.receive(RabbitTopology.INAPP_QUEUE, 250)).isNull();
            assertThat(template.receive(RabbitTopology.EMAIL_QUEUE, 250)).isNull();
        } finally {
            restoreTopicBindings();
        }
    }

    @Test
    void oneRemainingTopicBindingKeepsThePublishRoutable() {
        admin.removeBinding(topicBinding(RabbitTopology.INAPP_QUEUE));
        try {
            OrderEvent event = sampleEvent();
            publisher.publish(event);

            assertThat(template.receive(RabbitTopology.INAPP_QUEUE, 250)).isNull();
            Message email = template.receive(RabbitTopology.EMAIL_QUEUE, 5_000);
            assertThat(email).isNotNull();
            assertThat(email.getMessageProperties().getMessageId())
                    .isEqualTo(event.eventId().toString());
        } finally {
            admin.declareBinding(topicBinding(RabbitTopology.INAPP_QUEUE));
        }
    }

    private static void removeTopicBindings() {
        for (String queue : List.of(RabbitTopology.INAPP_QUEUE, RabbitTopology.EMAIL_QUEUE)) {
            admin.removeBinding(topicBinding(queue));
        }
    }

    private static void restoreTopicBindings() {
        for (String queue : List.of(RabbitTopology.INAPP_QUEUE, RabbitTopology.EMAIL_QUEUE)) {
            admin.declareBinding(topicBinding(queue));
        }
    }

    private static Binding topicBinding(String queue) {
        return new Binding(queue, Binding.DestinationType.QUEUE,
                RabbitTopology.EXCHANGE, RabbitTopology.ROUTING_PATTERN, null);
    }

    private static OrderEvent sampleEvent() {
        return OrderEvent.create(OrderEventType.ORDER_PLACED,
                "ORD_" + UUID.randomUUID().toString().substring(0, 8), 1L, OCCURRED_AT, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                new BigDecimal("199.90"), null, "corr-" + UUID.randomUUID(), "CHECKOUT_WEB");
    }
}
