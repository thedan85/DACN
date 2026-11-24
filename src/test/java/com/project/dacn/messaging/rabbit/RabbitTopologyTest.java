package com.project.dacn.messaging.rabbit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitTopologyTest {

    private static final Map<String, Integer> RETRY_TTL_MS =
            Map.of("10s", 10_000, "30s", 30_000, "120s", 120_000);

    private final Collection<Declarable> declarations =
            new RabbitTopologyConfig().notificationTopology().getDeclarables();

    @Test
    void declaresADurableTopicExchangeForOrderEvents() {
        Exchange exchange = exchange(RabbitTopology.EXCHANGE);

        assertThat(exchange.getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    void declaresDurableDirectRetryAndDeadLetterExchanges() {
        assertDirectExchange(RabbitTopology.RETRY_EXCHANGE);
        assertDirectExchange(RabbitTopology.DLX);
    }

    @Test
    void mainQueuesAreQuorumAndBoundToTheTopicAndRetryExchanges() {
        for (String name : List.of(RabbitTopology.INAPP_QUEUE, RabbitTopology.EMAIL_QUEUE)) {
            Queue queue = queue(name);

            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.isAutoDelete()).isFalse();
            assertThat(queue.isExclusive()).isFalse();
            assertThat(queue.getArguments())
                    .containsEntry("x-queue-type", "quorum")
                    .containsEntry("x-dead-letter-strategy", "at-least-once")
                    .containsEntry("x-overflow", "reject-publish")
                    .containsEntry("x-dead-letter-exchange", RabbitTopology.DLX)
                    .containsEntry("x-dead-letter-routing-key", name + ".dlq")
                    .containsEntry("x-delivery-limit", 4)
                    .doesNotContainKey("x-message-ttl")
                    .doesNotContainKey("x-expires");

            assertBinding(name, RabbitTopology.EXCHANGE, RabbitTopology.ROUTING_PATTERN);
            assertBinding(name, RabbitTopology.RETRY_EXCHANGE, name);
        }
    }

    @Test
    void retryQueuesCarryTheExactTtlAndDeadLetterBackToTheirMainQueue() {
        for (String mainQueue : List.of(RabbitTopology.INAPP_QUEUE, RabbitTopology.EMAIL_QUEUE)) {
            for (Map.Entry<String, Integer> retry : RETRY_TTL_MS.entrySet()) {
                String name = mainQueue + ".retry." + retry.getKey();
                Queue queue = queue(name);

                assertThat(queue.isDurable()).isTrue();
                assertThat(queue.isAutoDelete()).isFalse();
                assertThat(queue.isExclusive()).isFalse();
                assertThat(queue.getArguments())
                        .containsEntry("x-queue-type", "quorum")
                        .containsEntry("x-message-ttl", retry.getValue())
                        .containsEntry("x-dead-letter-strategy", "at-least-once")
                        .containsEntry("x-overflow", "reject-publish")
                        .containsEntry("x-dead-letter-exchange", RabbitTopology.RETRY_EXCHANGE)
                        .containsEntry("x-dead-letter-routing-key", mainQueue)
                        .doesNotContainKey("x-expires");

                assertBinding(name, RabbitTopology.RETRY_EXCHANGE, name);
            }
        }
    }

    @Test
    void deadLetterQueuesAreQuorumWithoutTtlAndBoundToTheDlx() {
        for (String mainQueue : List.of(RabbitTopology.INAPP_QUEUE, RabbitTopology.EMAIL_QUEUE)) {
            String name = mainQueue + ".dlq";
            Queue queue = queue(name);

            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.isAutoDelete()).isFalse();
            assertThat(queue.isExclusive()).isFalse();
            assertThat(queue.getArguments())
                    .containsEntry("x-queue-type", "quorum")
                    .doesNotContainKey("x-message-ttl")
                    .doesNotContainKey("x-expires");

            assertBinding(name, RabbitTopology.DLX, mainQueue + ".dlq");
        }
    }

    @Test
    void declaresNoUnnamedExtraQueues() {
        assertThat(declarations.stream()
                .filter(Queue.class::isInstance)
                .map(declarable -> ((Queue) declarable).getName()))
                .containsExactlyInAnyOrder(
                        RabbitTopology.INAPP_QUEUE,
                        RabbitTopology.EMAIL_QUEUE,
                        RabbitTopology.INAPP_QUEUE + ".retry.10s",
                        RabbitTopology.INAPP_QUEUE + ".retry.30s",
                        RabbitTopology.INAPP_QUEUE + ".retry.120s",
                        RabbitTopology.INAPP_QUEUE + ".dlq",
                        RabbitTopology.EMAIL_QUEUE + ".retry.10s",
                        RabbitTopology.EMAIL_QUEUE + ".retry.30s",
                        RabbitTopology.EMAIL_QUEUE + ".retry.120s",
                        RabbitTopology.EMAIL_QUEUE + ".dlq");
    }

    @Test
    void applicationConfiguresCorrelatedConfirmsWithMandatoryReturns() throws Exception {
        PropertySource<?> source = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(source.getProperty("spring.rabbitmq.publisher-confirm-type"))
                .isEqualTo("correlated");
        assertThat(source.getProperty("spring.rabbitmq.publisher-returns"))
                .isEqualTo(Boolean.TRUE);
        assertThat(source.getProperty("spring.rabbitmq.template.mandatory"))
                .isEqualTo(Boolean.TRUE);
    }

    private void assertDirectExchange(String name) {
        Exchange exchange = exchange(name);

        assertThat(exchange.getType()).isEqualTo(ExchangeTypes.DIRECT);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    private Queue queue(String name) {
        return declarations.stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .filter(queue -> queue.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing queue " + name));
    }

    private Exchange exchange(String name) {
        return declarations.stream()
                .filter(Exchange.class::isInstance)
                .map(Exchange.class::cast)
                .filter(exchange -> exchange.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing exchange " + name));
    }

    private void assertBinding(String destination, String exchange, String routingKey) {
        Binding binding = declarations.stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .filter(candidate -> candidate.getDestination().equals(destination)
                        && candidate.getExchange().equals(exchange))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing binding " + exchange + " -> " + destination));

        assertThat(binding.getRoutingKey()).isEqualTo(routingKey);
        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
    }
}
