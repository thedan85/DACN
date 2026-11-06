package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.messaging.rabbit.NotificationRabbitProperties;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import com.project.dacn.messaging.rabbit.RabbitTopologyConfig;
import com.project.dacn.messaging.rabbit.RabbitTopologyVerifier;
import com.project.dacn.messaging.rabbit.TopologyVerificationException;
import com.project.dacn.support.ExternalRabbit;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.AdditionalAnswers;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("external-rabbit-dacn-topology")
class RabbitRetryBrokerTest {

    private static final String MAIN_QUEUE = RabbitTopology.INAPP_QUEUE;
    private static final String RETRY_QUEUE = MAIN_QUEUE + ".retry.10s";
    private static final String DLQ = MAIN_QUEUE + ".dlq";
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FAILURE_TIMEOUT = Duration.ofSeconds(20);

    private static CachingConnectionFactory connectionFactory;
    private static RabbitTemplate template;
    private static RabbitAdmin admin;
    private static Connection connection;
    private static Channel channel;
    private static Collection<Declarable> declarations;
    private static ExternalRabbit.OwnedTopology ownedTopology;
    private static NotificationConsumerSupport support;
    private static RabbitTopologyVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        connectionFactory = ExternalRabbit.fromEnvironment().connectionFactory();
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        admin = new RabbitAdmin(connectionFactory);
        declarations = new RabbitTopologyConfig().notificationTopology().getDeclarables();
        ownedTopology = ExternalRabbit.declare(admin, declarations);
        connection = connectionFactory.createConnection();
        channel = connection.createChannel(false);

        NotificationRabbitProperties properties = new NotificationRabbitProperties();
        properties.setConfirmTimeout(Duration.ofSeconds(5));
        support = new NotificationConsumerSupport(
                new RabbitRetryPublisher(template, properties),
                new OrderEventAgeGuard(mock(DatabaseClock.class)));

        String managementUrl = System.getenv("TEST_RABBITMQ_MANAGEMENT_URL");
        if (managementUrl != null && !managementUrl.isBlank()) {
            verifier = createVerifier(managementUrl);
        }
    }

    @BeforeEach
    void purgeQueues() {
        ownedTopology.purgeQueues();
    }

    @AfterAll
    static void tearDown() {
        try {
            if (ownedTopology != null) {
                ownedTopology.close();
            }
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception ignored) {
                // The connection is already gone; cleanup still proceeds.
            }
            if (connectionFactory != null) {
                connectionFactory.destroy();
            }
        }
    }

    @Test
    void retryQueueDeadLettersBackToTheMainQueue() throws Exception {
        Message message = message(2);
        template.send(RabbitTopology.RETRY_EXCHANGE, RETRY_QUEUE, message);

        GetResponse handled = awaitMessage(MAIN_QUEUE, Duration.ofSeconds(15));

        assertThat(handled).as("retry queue should dead-letter to the main queue").isNotNull();
        assertThat(handled.getBody()).isEqualTo(message.getBody());
        assertThat(attempt(handled)).isEqualTo(2);
        channel.basicAck(handled.getEnvelope().getDeliveryTag(), false);
    }

    @Test
    void missingRetryRouteReturnsThePublishAndLeavesTheOriginalUnacknowledged() throws Exception {
        Binding retryRoute = retryBinding();
        admin.removeBinding(retryRoute);
        GetResponse original = null;
        try {
            template.send(RabbitTopology.RETRY_EXCHANGE, MAIN_QUEUE, message(0));
            original = awaitMessage(MAIN_QUEUE, Duration.ofSeconds(5));
            assertThat(original).isNotNull();

            Channel recording = recordingChannel();
            GetResponse unacknowledged = original;
            assertThatThrownBy(() -> support.handleTransientFailure(NotificationChannel.INAPP,
                    toMessage(unacknowledged), recording, null,
                    new IllegalStateException("boom")))
                    .isInstanceOfSatisfying(RetryTransferFailedException.class, failure ->
                            assertThat(failure.code()).isEqualTo(
                                    RetryTransferFailedException.ReasonCode.PUBLISH_UNROUTABLE));

            verify(recording, never()).basicAck(anyLong(), anyBoolean());
            assertThat(awaitMessage(RETRY_QUEUE, Duration.ofMillis(300))).isNull();
        } finally {
            try {
                rejectWithoutRequeue(original);
            } finally {
                admin.declareBinding(retryRoute);
            }
        }

        template.send(RabbitTopology.RETRY_EXCHANGE, MAIN_QUEUE, message(0));
        GetResponse retryOriginal = awaitMessage(MAIN_QUEUE, Duration.ofSeconds(5));
        assertThat(retryOriginal).isNotNull();

        Channel recording = recordingChannel();
        support.handleTransientFailure(NotificationChannel.INAPP, toMessage(retryOriginal),
                recording, null, new IllegalStateException("boom"));
        verify(recording).basicAck(retryOriginal.getEnvelope().getDeliveryTag(), false);

        GetResponse handled = awaitMessage(MAIN_QUEUE, Duration.ofSeconds(15));
        assertThat(handled).as("the restored route delivers retries again").isNotNull();
        assertThat(attempt(handled)).isEqualTo(1);
        channel.basicAck(handled.getEnvelope().getDeliveryTag(), false);
    }

    @Test
    void unacknowledgedOriginalIsNotVisibleToAnotherConsumer() throws Exception {
        template.send(RabbitTopology.RETRY_EXCHANGE, MAIN_QUEUE, message(0));
        GetResponse original = awaitMessage(MAIN_QUEUE, Duration.ofSeconds(5));
        assertThat(original).isNotNull();

        Channel other = connection.createChannel(false);
        try {
            assertThat(awaitMessage(other, MAIN_QUEUE, Duration.ofMillis(300)))
                    .as("an unacknowledged original must not be delivered twice")
                    .isNull();
        } finally {
            other.close();
        }
        channel.basicAck(original.getEnvelope().getDeliveryTag(), false);
    }

    @Test
    void unavailableDlqPathFailsTheForwardAndReadinessUntilTheRouteIsRestored() throws Exception {
        Binding dlqRoute = dlqBinding();
        admin.removeBinding(dlqRoute);
        GetResponse original = null;
        try {
            template.send(RabbitTopology.RETRY_EXCHANGE, MAIN_QUEUE, message(3));
            original = awaitMessage(MAIN_QUEUE, Duration.ofSeconds(5));
            assertThat(original).isNotNull();

            Channel recording = recordingChannel();
            GetResponse unacknowledged = original;
            assertThatThrownBy(() -> support.handlePermanentFailure(NotificationChannel.INAPP,
                    toMessage(unacknowledged), recording, null, "RETRIES_EXHAUSTED"))
                    .isInstanceOfSatisfying(RetryTransferFailedException.class, failure ->
                            assertThat(failure.code()).isEqualTo(
                                    RetryTransferFailedException.ReasonCode.PUBLISH_UNROUTABLE));

            verify(recording, never()).basicAck(anyLong(), anyBoolean());

            Assumptions.assumeTrue(verifier != null,
                    "TEST_RABBITMQ_MANAGEMENT_URL is not set; readiness sub-check skipped");
            TopologyVerificationException failure = awaitFailure(
                    TopologyVerificationException.Code.BINDING);
            assertThat(failure.queueName()).isNull();
            assertThat(failure.getMessage()).doesNotContain("localhost", "guest");
        } finally {
            try {
                rejectWithoutRequeue(original);
            } finally {
                admin.declareBinding(dlqRoute);
            }
        }

        awaitVerified();
    }

    private static Channel recordingChannel() {
        return mock(Channel.class, AdditionalAnswers.delegatesTo(channel));
    }

    private static void rejectWithoutRequeue(GetResponse response) throws Exception {
        if (response == null) {
            return;
        }
        // Cleanup only: reject without requeue so the failed delivery cannot
        // stay stuck on the shared channel. The application never nacks; a
        // requeue=true hot loop is exactly what the moved-DLQ path avoids.
        channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
    }

    @SuppressWarnings("unchecked")
    private static RabbitTopologyVerifier createVerifier(String managementUrl) {
        NotificationRabbitProperties properties = new NotificationRabbitProperties();
        properties.setManagementUrl(managementUrl);
        properties.setManagementUsername("guest");
        properties.setManagementPassword("guest");
        properties.setExpectedVersionPrefix(
                System.getenv().getOrDefault("TEST_RABBITMQ_EXPECTED_VERSION", "4.4"));

        ObjectProvider<RestClient.Builder> builders = mock(ObjectProvider.class);
        when(builders.getIfAvailable(any())).thenReturn(RestClient.builder());
        return new RabbitTopologyVerifier(builders, properties);
    }

    private static void awaitVerified() {
        long deadline = System.nanoTime() + VERIFY_TIMEOUT.toNanos();
        TopologyVerificationException last = null;
        while (System.nanoTime() < deadline) {
            try {
                verifier.verify();
                return;
            } catch (TopologyVerificationException e) {
                last = e;
                sleep();
            }
        }
        throw new AssertionError("topology did not verify within " + VERIFY_TIMEOUT
                + ", last code " + (last == null ? "none" : last.code()));
    }

    private static TopologyVerificationException awaitFailure(
            TopologyVerificationException.Code expected) {
        long deadline = System.nanoTime() + FAILURE_TIMEOUT.toNanos();
        TopologyVerificationException last = null;
        while (System.nanoTime() < deadline) {
            try {
                verifier.verify();
            } catch (TopologyVerificationException e) {
                if (e.code() == expected) {
                    return e;
                }
                last = e;
            }
            sleep();
        }
        throw new AssertionError("topology did not fail with " + expected
                + ", last code " + (last == null ? "none" : last.code()));
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the broker", e);
        }
    }

    private static Binding retryBinding() {
        return new Binding(RETRY_QUEUE, Binding.DestinationType.QUEUE,
                RabbitTopology.RETRY_EXCHANGE, RETRY_QUEUE, null);
    }

    private static Binding dlqBinding() {
        return new Binding(DLQ, Binding.DestinationType.QUEUE,
                RabbitTopology.DLX, DLQ, null);
    }

    private static GetResponse awaitMessage(String queue, Duration timeout) throws Exception {
        return awaitMessage(channel, queue, timeout);
    }

    private static GetResponse awaitMessage(Channel source, String queue, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            GetResponse response = source.basicGet(queue, false);
            if (response != null) {
                return response;
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static Message toMessage(GetResponse response) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(response.getEnvelope().getDeliveryTag());
        properties.setMessageId(response.getProps().getMessageId());
        properties.setCorrelationId(response.getProps().getCorrelationId());
        properties.setContentType(response.getProps().getContentType());
        properties.setTimestamp(response.getProps().getTimestamp());
        if (response.getProps().getHeaders() != null) {
            response.getProps().getHeaders().forEach(properties::setHeader);
        }
        return new Message(response.getBody(), properties);
    }

    private static Message message(int attempt) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setTimestamp(new Date());
        properties.setCorrelationId("corr-" + UUID.randomUUID());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader(RabbitRetryPublisher.RETRY_ATTEMPT_HEADER, attempt);
        properties.setHeader("x-event-type", "ORDER_PLACED");
        return new Message(("{\"attempt\":" + attempt + "}")
                .getBytes(StandardCharsets.UTF_8), properties);
    }

    private static int attempt(GetResponse response) {
        Object value = response.getProps().getHeaders()
                .get(RabbitRetryPublisher.RETRY_ATTEMPT_HEADER);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
