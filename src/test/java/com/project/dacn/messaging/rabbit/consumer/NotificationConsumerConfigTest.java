package com.project.dacn.messaging.rabbit.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Delivery-disabled gating and the single shared listener container settings. */
class NotificationConsumerConfigTest {

    @Test
    void noConsumerListenersOrPauseBeanWhenDeliveryDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(InAppNotificationConsumer.class,
                        EmailNotificationConsumer.class, EmailTransportPause.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(InAppNotificationConsumer.class);
                    assertThat(context).doesNotHaveBean(EmailNotificationConsumer.class);
                    assertThat(context).doesNotHaveBean(EmailTransportPause.class);
                });
    }

    @Test
    void sharedListenerFactoryUsesManualAckPrefetchTenConcurrencyOneAndNoAutoStart() {
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
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var factory = context.getBean("rabbitListenerContainerFactory",
                            SimpleRabbitListenerContainerFactory.class);
                    var env = context.getEnvironment();
                    assertThat(env.getProperty("spring.rabbitmq.listener.simple.prefetch"))
                            .isEqualTo("10");
                    assertThat(env.getProperty("spring.rabbitmq.listener.simple.concurrency"))
                            .isEqualTo("1");
                    assertThat(env.getProperty("spring.rabbitmq.listener.simple.acknowledge-mode"))
                            .isEqualTo("manual");
                    assertThat(factory.createListenerContainer(null).isAutoStartup()).isFalse();
                });
    }
}
