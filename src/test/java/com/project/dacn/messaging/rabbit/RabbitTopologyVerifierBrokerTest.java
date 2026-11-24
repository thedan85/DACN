package com.project.dacn.messaging.rabbit;

import com.project.dacn.support.ExternalRabbit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.time.Duration;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_MANAGEMENT_URL", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("external-rabbit-dacn-topology")
class RabbitTopologyVerifierBrokerTest {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FAILURE_TIMEOUT = Duration.ofSeconds(20);

    private static CachingConnectionFactory connectionFactory;
    private static RabbitAdmin admin;
    private static Collection<Declarable> declarations;
    private static ExternalRabbit.OwnedTopology ownedTopology;
    private static RabbitTopologyVerifier verifier;

    @BeforeAll
    static void setUp() {
        connectionFactory = ExternalRabbit.fromEnvironment().connectionFactory();
        admin = new RabbitAdmin(connectionFactory);
        declarations = new RabbitTopologyConfig().notificationTopology().getDeclarables();
        ownedTopology = ExternalRabbit.declare(admin, declarations);

        NotificationRabbitProperties properties = new NotificationRabbitProperties();
        properties.setManagementUrl(System.getenv("TEST_RABBITMQ_MANAGEMENT_URL"));
        properties.setManagementUsername("guest");
        properties.setManagementPassword("guest");
        properties.setExpectedVersionPrefix(
                System.getenv().getOrDefault("TEST_RABBITMQ_EXPECTED_VERSION", "4.4"));

        verifier = new RabbitTopologyVerifier(
                RabbitTopologyVerifier.buildClient(), properties, Duration.ZERO);
    }

    @AfterAll
    static void tearDown() {
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

    @Test
    void verifiesTheTopologyFailsWhenABindingDisappearsAndRecoversAfterRestore() {
        awaitVerified();

        Binding inappTopicBinding = new Binding(RabbitTopology.INAPP_QUEUE,
                Binding.DestinationType.QUEUE, RabbitTopology.EXCHANGE,
                RabbitTopology.ROUTING_PATTERN, null);
        admin.removeBinding(inappTopicBinding);
        try {
            TopologyVerificationException failure = awaitFailure(
                    TopologyVerificationException.Code.BINDING);

            assertThat(failure.queueName()).isNull();
            assertThat(failure.getMessage()).doesNotContain("localhost", "guest");
        } finally {
            admin.declareBinding(inappTopicBinding);
        }

        awaitVerified();
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
}
