package com.project.dacn.messaging.rabbit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RabbitTopologyVerifierTest {

    private static final String MANAGEMENT_URL = "http://management.local";
    private static final String USERNAME = "monitor";
    private static final String PASSWORD = "management-secret";
    private static final String OVERVIEW_URL = MANAGEMENT_URL + "/api/overview";
    private static final String FEATURE_FLAGS_URL = MANAGEMENT_URL + "/api/feature-flags";
    private static final String BINDINGS_URL = MANAGEMENT_URL + "/api/bindings";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Collection<Declarable> DECLARATIONS =
            new RabbitTopologyConfig().notificationTopology().getDeclarables();
    private static final List<Queue> DECLARED_QUEUES = DECLARATIONS.stream()
            .filter(Queue.class::isInstance)
            .map(Queue.class::cast)
            .toList();

    private MockRestServiceServer server;
    private RabbitTopologyVerifier verifier;
    private ManagementApi api;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        NotificationRabbitProperties properties = properties();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        // Keep the existing API fixtures while exercising the verifier's async
        // response path. Local HTTP tests use the production transport directly.
        RestClient responses = builder.build();
        HttpClient client = mock(HttpClient.class);
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            var result = responses.get().uri(request.uri())
                    .headers(headers -> request.headers().map().forEach(headers::put))
                    .retrieve().toEntity(byte[].class);
            HttpResponse<byte[]> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(result.getStatusCode().value());
            when(response.body()).thenReturn(result.getBody());
            return CompletableFuture.completedFuture(response);
        });
        verifier = new RabbitTopologyVerifier(client, properties, Duration.ZERO);
        api = new ManagementApi();
    }

    @Test
    void failsWithBrokerVersionWhenThePrefixDoesNotMatch() {
        api.version = "3.13.7";

        api.stub();

        assertFailure(TopologyVerificationException.Code.BROKER_VERSION, null);
    }

    @Test
    void failsWithFeatureFlagWhenStreamQueueIsMissing() {
        api.streamQueuePresent = false;

        api.stub();

        assertFailure(TopologyVerificationException.Code.FEATURE_FLAG, null);
    }

    @Test
    void failsWithFeatureFlagWhenStreamQueueIsDisabled() {
        api.streamQueueState = "disabled";

        api.stub();

        assertFailure(TopologyVerificationException.Code.FEATURE_FLAG, null);
    }

    @Test
    void failsWithQueuePolicyWhenAQueueIsMissing() {
        api.dropQueue(RabbitTopology.EMAIL_QUEUE);

        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.EMAIL_QUEUE);
    }

    @Test
    void failsWithQueuePolicyWhenTheQueueTypeIsWrong() {
        api.mutateQueue(RabbitTopology.INAPP_QUEUE, payload -> payload.put("type", "classic"));

        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.INAPP_QUEUE);
    }

    @Test
    void failsWithQueuePolicyWhenTheDeadLetterStrategyIsWrong() {
        api.mutateArguments(RabbitTopology.INAPP_QUEUE,
                arguments -> arguments.put("x-dead-letter-strategy", "transient"));

        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.INAPP_QUEUE);
    }

    @Test
    void failsWithQueuePolicyWhenOverflowIsWrong() {
        api.mutateArguments(RabbitTopology.EMAIL_QUEUE,
                arguments -> arguments.put("x-overflow", "reject-publish-dlx"));

        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.EMAIL_QUEUE);
    }

    @Test
    void failsWithQueuePolicyWhenTheDeadLetterExchangeIsWrong() {
        api.mutateArguments(RabbitTopology.INAPP_QUEUE,
                arguments -> arguments.put("x-dead-letter-exchange", "dacn.wrong.dlx"));

        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.INAPP_QUEUE);
    }

    @Test
    void failsWithQueuePolicyWhenTheDeadLetterRoutingKeyIsWrong() {
        api.mutateArguments(RabbitTopology.EMAIL_QUEUE,
                arguments -> arguments.put("x-dead-letter-routing-key", "dacn.wrong.dlq"));

        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.EMAIL_QUEUE);
    }

    @Test
    void failsWithQueuePolicyAndTheQueueNameWhenARetryTtlIsWrong() {
        String retryQueue = RabbitTopology.INAPP_QUEUE + ".retry.30s";
        api.mutateArguments(retryQueue, arguments -> arguments.put("x-message-ttl", 60_000));

        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, retryQueue);
    }

    @Test
    void failsWithBindingWhenAnExpectedBindingIsMissing() {
        api.dropBinding(RabbitTopology.EXCHANGE, RabbitTopology.EMAIL_QUEUE,
                RabbitTopology.ROUTING_PATTERN);

        api.stub();

        assertFailure(TopologyVerificationException.Code.BINDING, null);
    }

    @Test
    void passesWhenEveryManagementCheckMatches() {
        api.stub();

        assertThatCode(verifier::verify).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void acceptsPolicySuppliedDeadLetterArguments() {
        api.mutateQueue(RabbitTopology.INAPP_QUEUE, payload -> {
            Map<String, Object> arguments = arguments(payload);
            Map<String, Object> policy = new LinkedHashMap<>();
            for (String key : List.of("x-dead-letter-strategy", "x-overflow",
                    "x-dead-letter-exchange", "x-dead-letter-routing-key", "x-delivery-limit")) {
                policy.put(key.substring(2), arguments.remove(key));
            }
            payload.put("effective_policy_definition", policy);
            payload.put("policy", "dacn-topology");
        });

        api.stub();

        assertThatCode(verifier::verify).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void failureCarriesOnlyABoundedCodeAndQueueName() {
        api.version = "3.13.7";
        api.stub();

        TopologyVerificationException failure = assertFailure(
                TopologyVerificationException.Code.BROKER_VERSION, null);

        assertThat(failure.getMessage())
                .contains("BROKER_VERSION")
                .doesNotContain(MANAGEMENT_URL)
                .doesNotContain(PASSWORD);
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void runnerRetriesTheWholeVerificationSixTimesBeforeFailing() {
        AtomicInteger attempts = new AtomicInteger();
        RabbitTopologyVerifier runner = new RabbitTopologyVerifier(
                RabbitTopologyVerifier.buildClient(), properties(), Duration.ZERO) {
            @Override
            void verify(long deadline) {
                attempts.incrementAndGet();
                throw new TopologyVerificationException(
                        TopologyVerificationException.Code.BINDING);
            }
        };

        TopologyVerificationException failure = catchThrowableOfType(
                () -> runner.run(null), TopologyVerificationException.class);

        assertThat(attempts).hasValue(6);
        assertThat(failure.code()).isEqualTo(TopologyVerificationException.Code.BINDING);
    }

    @Test
    void failsWithVerificationTimeoutWhenTheBudgetIsAlreadyExhausted() {
        RabbitTopologyVerifier expired = new RabbitTopologyVerifier(
                RabbitTopologyVerifier.buildClient(), properties(), Duration.ZERO, Duration.ZERO);

        TopologyVerificationException failure = catchThrowableOfType(
                expired::verify, TopologyVerificationException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.code())
                .isEqualTo(TopologyVerificationException.Code.VERIFICATION_TIMEOUT);
        assertThat(failure.queueName()).isNull();
        assertThat(failure.getMessage())
                .contains("VERIFICATION_TIMEOUT")
                .doesNotContain(MANAGEMENT_URL)
                .doesNotContain(PASSWORD);
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void rejectsAFinalBindingsResponseThatCompletesAfterAPositiveDeadline() {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        api.beforeBindings = () -> {
            assertThat(deadline - System.nanoTime()).isPositive();
            sleepNanos(deadline - System.nanoTime() + Duration.ofMillis(50).toNanos());
        };
        api.stub();

        TopologyVerificationException failure = catchThrowableOfType(
                () -> verifier.verify(deadline), TopologyVerificationException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.code())
                .isEqualTo(TopologyVerificationException.Code.VERIFICATION_TIMEOUT);
        assertThat(failure.getCause()).isNull();
        server.verify();
    }

    @Test
    void cancelsASlowFinalBindingsBodyAtTheRemainingDeadline() throws IOException {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean finalResponseStarted = new AtomicBoolean();
        AtomicBoolean finalResponseCompleted = new AtomicBoolean();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        local.createContext("/api/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getRawPath();
                Object payload;
                if (path.equals("/api/overview")) {
                    payload = Map.of("rabbitmq_version", "4.4.2");
                } else if (path.equals("/api/feature-flags")) {
                    payload = List.of(Map.of("name", "stream_queue", "state", "enabled"));
                } else if (path.equals("/api/bindings")) {
                    finalResponseStarted.set(System.nanoTime() < deadline);
                    payload = bindingPayload();
                } else {
                    payload = DECLARED_QUEUES.stream()
                            .filter(queue -> path.equals("/api/queues/%2F/" + queue.getName()))
                            .findFirst().map(RabbitTopologyVerifierTest::queuePayload).orElseThrow();
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                byte[] body = JSON.writeValueAsBytes(payload);
                if (path.equals("/api/bindings")) {
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    while (System.nanoTime() < deadline + Duration.ofSeconds(1).toNanos()) {
                        exchange.getResponseBody().write(' ');
                        exchange.getResponseBody().flush();
                        sleepNanos(Duration.ofMillis(20).toNanos());
                    }
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                    finalResponseCompleted.set(true);
                } else {
                    exchange.getResponseBody().write(body);
                }
            } catch (IOException ignored) {
                // Cancellation closes the connection before the delayed body finishes.
            }
        });
        local.start();
        try {
            NotificationRabbitProperties properties = properties();
            properties.setManagementUrl("http://127.0.0.1:" + local.getAddress().getPort());
            RabbitTopologyVerifier bounded = new RabbitTopologyVerifier(
                    RabbitTopologyVerifier.buildClient(),
                    properties, Duration.ZERO);

            TopologyVerificationException failure = catchThrowableOfType(
                    () -> bounded.verify(deadline), TopologyVerificationException.class);

            assertThat(finalResponseStarted).isTrue();
            assertThat(failure).isNotNull();
            assertThat(failure.code())
                    .isEqualTo(TopologyVerificationException.Code.VERIFICATION_TIMEOUT);
            assertThat(finalResponseCompleted).isFalse();
            assertThat(failure.queueName()).isNull();
            assertThat(failure.getMessage()).doesNotContain("127.0.0.1", PASSWORD);
            assertThat(failure.getCause()).isNull();
        } finally {
            local.stop(0);
        }
    }

    private static void sleepNanos(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(Math.max(0, nanos));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while serving a test response", interrupted);
        }
    }

    @Test
    void runnerStopsRetryingOnceTheBudgetIsExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        RabbitTopologyVerifier runner = new RabbitTopologyVerifier(
                RabbitTopologyVerifier.buildClient(), properties(), Duration.ZERO, Duration.ZERO) {
            @Override
            void verify(long deadline) {
                attempts.incrementAndGet();
                throw new TopologyVerificationException(
                        TopologyVerificationException.Code.BINDING);
            }
        };

        TopologyVerificationException failure = catchThrowableOfType(
                () -> runner.run(null), TopologyVerificationException.class);

        assertThat(failure.code()).isEqualTo(TopologyVerificationException.Code.BINDING);
        assertThat(attempts.get())
                .isBetween(1, RabbitTopologyVerifier.MAX_ATTEMPTS);
    }

    @Test
    void healthIndicatorCachesTheUpResult() {
        RabbitTopologyVerifier probe = mock(RabbitTopologyVerifier.class);
        RabbitTopologyHealthIndicator indicator =
                new RabbitTopologyHealthIndicator(Optional.of(probe));

        Health first = indicator.health();
        Health second = indicator.health();

        assertThat(first.getStatus()).isEqualTo(Status.UP);
        assertThat(second).isSameAs(first);
        verify(probe, times(1)).verify();
    }

    @Test
    void healthIndicatorCachesTheDownResult() {
        RabbitTopologyVerifier probe = mock(RabbitTopologyVerifier.class);
        doThrow(new TopologyVerificationException(
                TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.EMAIL_QUEUE))
                .when(probe).verify();
        RabbitTopologyHealthIndicator indicator =
                new RabbitTopologyHealthIndicator(Optional.of(probe));

        Health first = indicator.health();
        Health second = indicator.health();

        assertThat(first.getStatus()).isEqualTo(Status.DOWN);
        assertThat(second).isSameAs(first);
        verify(probe, times(1)).verify();
    }

    @Test
    void deliveryDisabledLeavesTheVerifierAbsentAndTheHealthIndicatorUp() {
        NotificationRabbitProperties properties = properties();

        new ApplicationContextRunner()
                .withPropertyValues("app.notifications.delivery-enabled=false")
                .withBean(NotificationRabbitProperties.class, () -> properties)
                .withUserConfiguration(RabbitTopologyVerifier.class,
                        RabbitTopologyHealthIndicator.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RabbitTopologyVerifier.class);
                    assertThat(context).hasBean("rabbitTopology");

                    Health health = context.getBean("rabbitTopology", HealthIndicator.class).health();

                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("delivery", "disabled");
                });
    }

    @Test
    void deliveryEnabledRegistersTheVerifierBean() {
        NotificationRabbitProperties properties = properties();

        new ApplicationContextRunner()
                .withPropertyValues("app.notifications.delivery-enabled=true")
                .withBean(NotificationRabbitProperties.class, () -> properties)
                .withUserConfiguration(RabbitTopologyVerifier.class,
                        RabbitTopologyHealthIndicator.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RabbitTopologyVerifier.class);
                    assertThat(context).hasBean("rabbitTopology");
                });
    }

    @Test
    void healthIndicatorReportsDownWithOnlyTheBoundedFailureDetails() {
        RabbitTopologyVerifier failing = mock(RabbitTopologyVerifier.class);
        doThrow(new TopologyVerificationException(
                TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.EMAIL_QUEUE))
                .when(failing).verify();

        Health health = new RabbitTopologyHealthIndicator(Optional.of(failing)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsOnly(
                        Map.entry("code", "QUEUE_POLICY"),
                        Map.entry("queue", RabbitTopology.EMAIL_QUEUE));
    }

    private TopologyVerificationException assertFailure(
            TopologyVerificationException.Code code, String queueName) {
        TopologyVerificationException failure = catchThrowableOfType(
                verifier::verify, TopologyVerificationException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.queueName()).isEqualTo(queueName);
        return failure;
    }

    private static NotificationRabbitProperties properties() {
        NotificationRabbitProperties properties = new NotificationRabbitProperties();
        properties.setManagementUrl(MANAGEMENT_URL);
        properties.setManagementUsername(USERNAME);
        properties.setManagementPassword(PASSWORD);
        properties.setExpectedVersionPrefix("4.4");
        return properties;
    }

    private static String basicAuthorization() {
        String credentials = USERNAME + ":" + PASSWORD;
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String queueUrl(String queue) {
        return MANAGEMENT_URL + "/api/queues/%2F/" + queue;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("arguments");
    }

    private static Map<String, Object> queuePayload(Queue queue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", queue.getName());
        payload.put("type", queue.getArguments().getOrDefault("x-queue-type", "quorum"));
        payload.put("durable", queue.isDurable());
        payload.put("auto_delete", queue.isAutoDelete());
        payload.put("arguments", new LinkedHashMap<>(queue.getArguments()));
        payload.put("effective_policy_definition", new LinkedHashMap<>());
        payload.put("policy", "");
        return payload;
    }

    private static List<Map<String, Object>> bindingPayload() {
        return DECLARATIONS.stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .map(binding -> Map.<String, Object>of(
                        "source", binding.getExchange(),
                        "destination", binding.getDestination(),
                        "destination_type", "queue",
                        "routing_key", binding.getRoutingKey(),
                        "vhost", "/"))
                .toList();
    }

    private static String bindingKey(String source, String destination, String routingKey) {
        return source + "|" + destination + "|" + routingKey;
    }

    private final class ManagementApi {

        private String version = "4.4.2";
        private boolean streamQueuePresent = true;
        private String streamQueueState = "enabled";
        private Runnable beforeBindings = () -> { };
        private final Set<String> droppedQueues = new HashSet<>();
        private final Set<String> droppedBindings = new HashSet<>();
        private final Map<String, Consumer<Map<String, Object>>> queueCustomizers =
                new HashMap<>();

        private void dropQueue(String queue) {
            droppedQueues.add(queue);
        }

        private void dropBinding(String source, String destination, String routingKey) {
            droppedBindings.add(bindingKey(source, destination, routingKey));
        }

        private void mutateQueue(String queue, Consumer<Map<String, Object>> customizer) {
            queueCustomizers.put(queue, customizer);
        }

        private void mutateArguments(String queue, Consumer<Map<String, Object>> customizer) {
            queueCustomizers.put(queue,
                    payload -> customizer.accept(arguments(payload)));
        }

        private void stub() {
            server.expect(requestTo(OVERVIEW_URL))
                    .andExpect(header("Authorization", basicAuthorization()))
                    .andRespond(withSuccess(
                            json(Map.of("rabbitmq_version", version)),
                            MediaType.APPLICATION_JSON));

            List<Map<String, Object>> flags = new ArrayList<>();
            if (streamQueuePresent) {
                flags.add(Map.of("name", "stream_queue", "state", streamQueueState));
            }
            flags.add(Map.of("name", "maintenance_mode", "state", "disabled"));
            server.expect(requestTo(FEATURE_FLAGS_URL))
                    .andRespond(withSuccess(json(flags), MediaType.APPLICATION_JSON));

            for (Queue queue : DECLARED_QUEUES) {
                String url = queueUrl(queue.getName());
                if (droppedQueues.contains(queue.getName())) {
                    server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND));
                    continue;
                }
                Map<String, Object> payload = queuePayload(queue);
                Consumer<Map<String, Object>> customizer = queueCustomizers.get(queue.getName());
                if (customizer != null) {
                    customizer.accept(payload);
                }
                server.expect(requestTo(url))
                        .andRespond(withSuccess(json(payload), MediaType.APPLICATION_JSON));
            }

            List<Map<String, Object>> bindings = bindingPayload().stream()
                    .filter(binding -> !droppedBindings.contains(bindingKey(
                            (String) binding.get("source"),
                            (String) binding.get("destination"),
                            (String) binding.get("routing_key"))))
                    .toList();
            server.expect(requestTo(BINDINGS_URL))
                    .andRespond(request -> {
                        beforeBindings.run();
                        return withSuccess(json(bindings), MediaType.APPLICATION_JSON)
                                .createResponse(request);
                    });
        }

        private String json(Object value) {
            return JSON.writeValueAsString(value);
        }
    }
}
