package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.rabbit.NotificationRabbitProperties;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import com.project.dacn.messaging.rabbit.RabbitTopologyConfig;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.service.engagement.NotificationFanoutService;
import com.project.dacn.support.ExternalRabbit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Native-broker proof of retry-expiry retention after original ACK, stable event
 * identity, missing-route restoration and eventual delivery. Uses a disposable,
 * exclusively owned local broker target; all creations are recorded and removed.
 */
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_MANAGEMENT_URL", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("external-rabbit-dacn-topology")
class NotificationConsumerBrokerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(35);
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final byte[] BODY = "{\"eventId\":\"broker\"}".getBytes(StandardCharsets.UTF_8);

    private static CachingConnectionFactory connectionFactory;
    private static RabbitAdmin admin;
    private static RabbitTemplate rabbitTemplate;
    private static ExternalRabbit.OwnedTopology ownedTopology;
    private static NotificationFanoutService fanout;
    private static AtomicBoolean failFanout;
    private static AtomicInteger deliveries;
    private static SimpleMessageListenerContainer container;
    private static UUID eventId;

    @BeforeAll
    static void setUp() {
        connectionFactory = ExternalRabbit.fromEnvironment().connectionFactory();
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        admin = new RabbitAdmin(connectionFactory);
        java.util.Collection<Declarable> declarations =
                new RabbitTopologyConfig().notificationTopology().getDeclarables();
        // Empty-object preflight + exclusive fixture ownership happen here.
        ownedTopology = ExternalRabbit.declare(admin, declarations);

        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);

        DatabaseClock clock = mock(DatabaseClock.class);
        when(clock.now()).thenReturn(NOW);
        OrderEventCodec codec = mock(OrderEventCodec.class);
        OrderEvent event = event();
        eventId = event.eventId();
        when(codec.decode(any())).thenReturn(event);

        NotificationRabbitProperties properties = new NotificationRabbitProperties();
        properties.setConfirmTimeout(Duration.ofSeconds(5));
        NotificationConsumerSupport support = new NotificationConsumerSupport(
                new RabbitRetryPublisher(rabbitTemplate, properties), new OrderEventAgeGuard(clock));

        fanout = mock(NotificationFanoutService.class);
        failFanout = new AtomicBoolean(true);
        deliveries = new AtomicInteger();
        doThrow(new IllegalStateException("injected handler failure"))
                .when(fanout).handle(any());
        InAppNotificationConsumer consumer = new InAppNotificationConsumer(codec, support, fanout);

        container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(RabbitTopology.INAPP_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(10);
        container.setConcurrentConsumers(1);
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            deliveries.incrementAndGet();
            if (!failFanout.get()) {
                org.mockito.Mockito.doNothing().when(fanout).handle(any());
            }
            consumer.receive(message, channel);
        });
        container.start();
    }

    @AfterAll
    static void tearDown() {
        try {
            if (container != null) {
                container.stop();
                container.destroy();
            }
        } finally {
            try {
                if (ownedTopology != null) {
                    ownedTopology.close();
                }
            } finally {
                if (connectionFactory != null) {
                    connectionFactory.destroy();
                }
            }
        }
    }

    @Test
    void retryExpiryRetainsIdentityAndDeliversAfterMissingRouteIsRestored() {
        publishToQueue(RabbitTopology.INAPP_QUEUE, eventId, 0);

        // Original consumed once, confirmed retry published, original ACKed: no hot requeue.
        await(() -> deliveries.get() == 1 && count(RabbitTopology.INAPP_QUEUE) == 0
                && count(RabbitTopology.INAPP_QUEUE + ".retry.10s") >= 1, "retry published and ACKed");
        assertThat(count(RabbitTopology.INAPP_QUEUE + ".dlq")).isZero();

        // Stop consuming so retention can be observed without a second handler call.
        container.stop();
        assertThat(deliveries.get()).isEqualTo(1);

        // Remove the retry destination route, then let the retry message expire.
        Binding retryRoute = new Binding(RabbitTopology.INAPP_QUEUE,
                Binding.DestinationType.QUEUE, RabbitTopology.RETRY_EXCHANGE,
                RabbitTopology.INAPP_QUEUE, null);
        admin.removeBinding(retryRoute);
        try {
            // Quorum at-least-once dead-lettering keeps the message while unroutable:
            // it must be retained, never silently deleted.
            await(() -> count(RabbitTopology.INAPP_QUEUE + ".retry.10s") >= 1
                    && count(RabbitTopology.INAPP_QUEUE) == 0, "unroutable retry retained");
        } finally {
            admin.declareBinding(retryRoute);
        }

        // Restored route: TTL expiry dead-letters the retained message to the main queue.
        await(() -> count(RabbitTopology.INAPP_QUEUE) >= 1, "retry returned after route restore");
        com.rabbitmq.client.GetResponse returned = peekAndRequeue(RabbitTopology.INAPP_QUEUE);
        assertThat(returned != null).isTrue();
        assertThat(returned.getProps().getMessageId()).isEqualTo(eventId.toString());
        assertThat(returned.getProps().getHeaders().get(
                RabbitRetryPublisher.RETRY_ATTEMPT_HEADER)).isEqualTo(1);
        assertThat(count(RabbitTopology.INAPP_QUEUE + ".dlq")).isZero();

        // Eventual delivery: the stable-identity redelivery is handled once and ACKed.
        failFanout.set(false);
        container.start();
        await(() -> deliveries.get() == 2 && count(RabbitTopology.INAPP_QUEUE) == 0,
                "eventual delivery and ACK");
        assertThat(count(RabbitTopology.INAPP_QUEUE + ".retry.10s")).isZero();
        assertThat(count(RabbitTopology.INAPP_QUEUE + ".dlq")).isZero();
        System.out.println("TASK11_BROKER_EVIDENCE deliveries=" + deliveries.get()
                + " eventIdStable=true retryRetainedAfterAck=true routeRestored=true"
                + " dlqEmpty=true");
    }

    private static void publishToQueue(String queue, UUID id, int attempt) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(id.toString());
        properties.setTimestamp(Date.from(NOW));
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader(RabbitRetryPublisher.RETRY_ATTEMPT_HEADER, attempt);
        rabbitTemplate.send("", queue, new Message(BODY, properties));
    }

    private static long count(String queue) {
        Integer value = rabbitTemplate.execute(channel ->
                channel.queueDeclarePassive(queue).getMessageCount());
        return value == null ? 0 : value.longValue();
    }

    /** Peek identity, then requeue so the live container can still deliver the message. */
    private static com.rabbitmq.client.GetResponse peekAndRequeue(String queue) {
        return rabbitTemplate.execute(channel -> {
            com.rabbitmq.client.GetResponse response = channel.basicGet(queue, false);
            if (response != null) {
                channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
            }
            return response;
        });
    }

    private static void await(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what, e);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static OrderEvent event() {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_t11_broker", 1L, NOW, 42L,
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                new BigDecimal("10.00"), null, "t11-broker", "TEST");
    }
}
