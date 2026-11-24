package com.project.dacn.messaging.rabbit;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.OrderEventPublisher;
import com.project.dacn.messaging.outbox.ClaimedOrderEvent;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.messaging.outbox.OrderEventRelay;
import com.project.dacn.messaging.outbox.OutboxRelayRepository;
import com.project.dacn.messaging.outbox.OutboxTelemetry;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitDeliveryStartupTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingAndFailedVerificationCannotClaimPublishOrStartListeners(boolean succeeds) throws Exception {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        RabbitTopologyVerifier verifier = mock(RabbitTopologyVerifier.class);
        RabbitListenerEndpointRegistry registry = new RabbitListenerEndpointRegistry();
        MessageListenerContainer listener = mock(MessageListenerContainer.class);
        RabbitListenerEndpoint endpoint = mock(RabbitListenerEndpoint.class);
        when(endpoint.getId()).thenReturn("fixture");
        when(listener.isAutoStartup()).thenReturn(false);
        registry.registerListenerContainer(endpoint, ignored -> listener);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        doAnswer(ignored -> {
            entered.countDown();
            assertThat(complete.await(5, TimeUnit.SECONDS)).isTrue();
            if (!succeeds) {
                throw new TopologyVerificationException(TopologyVerificationException.Code.BINDING);
            }
            return null;
        }).when(verifier).run(any());

        Instant now = Instant.parse("2026-11-06T00:00:00Z");
        OrderEvent event = OrderEvent.create(OrderEventType.ORDER_PLACED, "ORD_STARTUP", 1L,
                now, 42L, OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                new BigDecimal("199.90"), null, "startup-test", "CHECKOUT_WEB");
        OutboxRelayRepository repository = mock(OutboxRelayRepository.class);
        OrderEventCodec codec = mock(OrderEventCodec.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        OrderEventPublisher publisher = mock(OrderEventPublisher.class);
        ClaimedOrderEvent due = new ClaimedOrderEvent(1L, event.eventId(), 1L, 1L,
                "ORDER_PLACED", "{}", 1, UUID.randomUUID());
        when(repository.claimDue(anyInt(), any(), any(), anyString(), any()))
                .thenReturn(List.of(due));
        when(codec.decode(any())).thenReturn(event);
        when(clock.now()).thenReturn(now);

        new ApplicationContextRunner()
                .withUserConfiguration(RabbitTopologyConfig.class, OrderEventRelay.class)
                .withPropertyValues("app.notifications.delivery-enabled=true")
                .withBean(AmqpAdmin.class, () -> admin)
                .withBean(RabbitTopologyVerifier.class, () -> verifier)
                .withBean(RabbitListenerEndpointRegistry.class, () -> registry)
                .withBean(OutboxRelayRepository.class, () -> repository)
                .withBean(OrderEventCodec.class, () -> codec)
                .withBean(DatabaseClock.class, () -> clock)
                .withBean(OrderEventAgeGuard.class, () -> new OrderEventAgeGuard(clock))
                .withBean(OrderEventPublisher.class, () -> publisher)
                .withBean(OutboxTelemetry.class, () -> mock(OutboxTelemetry.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OrderEventRelay relay = context.getBean(OrderEventRelay.class);
                    try (var executor = Executors.newSingleThreadExecutor()) {
                        var startup = executor.submit(() -> {
                            for (ApplicationRunner runner : context.getBeansOfType(ApplicationRunner.class).values()) {
                                runner.run(new DefaultApplicationArguments());
                            }
                            return null;
                        });
                        try {
                            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                            relay.poll();
                            verify(repository, never()).claimDue(anyInt(), any(), any(), anyString(), any());
                            verify(publisher, never()).publish(any());
                            verify(listener, never()).start();
                        } finally {
                            complete.countDown();
                        }
                        if (succeeds) {
                            startup.get(5, TimeUnit.SECONDS);
                            relay.poll();
                            var order = inOrder(admin, verifier, listener, repository, publisher);
                            order.verify(admin).initialize();
                            order.verify(verifier).run(any());
                            order.verify(listener).start();
                            order.verify(repository).claimDue(anyInt(), any(), any(), anyString(), any());
                            order.verify(publisher).publish(event);
                        } else {
                            assertThatThrownBy(() -> startup.get(5, TimeUnit.SECONDS))
                                    .isInstanceOf(ExecutionException.class)
                                    .hasCauseInstanceOf(TopologyVerificationException.class);
                            relay.poll();
                            verify(repository, never()).claimDue(anyInt(), any(), any(), anyString(), any());
                            verify(publisher, never()).publish(any());
                            verify(listener, never()).start();
                        }
                    }
                });
    }

    @Test
    void bootAutoConfigurationCanWireTheStartupWithoutAConcreteAdminBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                .withUserConfiguration(RabbitTopologyConfig.class)
                .withPropertyValues("app.notifications.delivery-enabled=true")
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(RabbitTopologyVerifier.class, () -> mock(RabbitTopologyVerifier.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AmqpAdmin.class)).isInstanceOf(RabbitAdmin.class);
                    assertThat(context).hasSingleBean(RabbitDeliveryStartup.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "direct"})
    void productionListenerFactoriesDoNotAutoStart(String type) {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        for (var source : new YamlPropertySourceLoader().load("application",
                                new ClassPathResource("application.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                .withPropertyValues("spring.rabbitmq.listener.type=" + type)
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var factory = context.getBean("rabbitListenerContainerFactory",
                            AbstractRabbitListenerContainerFactory.class);
                    assertThat(factory.createListenerContainer(null).isAutoStartup()).isFalse();
                    verify(context.getBean(ConnectionFactory.class), never()).createConnection();
                });
    }
}
