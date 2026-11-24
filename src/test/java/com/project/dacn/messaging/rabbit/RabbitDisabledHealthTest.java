package com.project.dacn.messaging.rabbit;

import com.project.dacn.messaging.outbox.OrderEventRelay;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.health.RabbitHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RabbitDisabledHealthTest {

    @Test
    void aggregateActuatorHealthDoesNotConnectWhenDeliveryIsDisabled() {
        AtomicInteger attempts = new AtomicInteger();
        ConnectionFactory recording = mock(ConnectionFactory.class);
        when(recording.createConnection()).thenAnswer(ignored -> {
            attempts.incrementAndGet();
            throw new AmqpConnectException(new ConnectException("Recording factory: no broker allowed"));
        });

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
                .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class,
                        RabbitHealthContributorAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class,
                        HealthContributorRegistryAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class))
                .withUserConfiguration(RabbitTopologyConfig.class, RabbitTopologyVerifier.class,
                        RabbitTopologyHealthIndicator.class, OrderEventRelay.class)
                .withPropertyValues("app.notifications.delivery-enabled=false", "spring.rabbitmq.dynamic=false")
                .withBean(ConnectionFactory.class, () -> recording)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var health = context.getBean(HealthEndpoint.class).health();
                    assertThat(attempts.get()).isZero();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(context).doesNotHaveBean(RabbitTopologyVerifier.class)
                            .doesNotHaveBean(RabbitDeliveryStartup.class)
                            .doesNotHaveBean(OrderEventRelay.class);
                    assertThat(context.getBean(RabbitTopologyHealthIndicator.class).health().getDetails())
                            .containsEntry("delivery", "disabled");
                });
    }
}
