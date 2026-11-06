package com.project.dacn.messaging.rabbit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Proves the broker's effective topology through the RabbitMQ management API.
 * Declarations alone are not proof: queues, bindings, policies, and feature
 * flags are read back from the running broker.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.delivery-enabled", havingValue = "true")
public class RabbitTopologyVerifier {

    static final int MAX_ATTEMPTS = 6;
    static final Duration TOTAL_BUDGET = Duration.ofSeconds(20);

    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    private static final HttpClient MANAGEMENT_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .executor(ForkJoinPool.commonPool())
            .build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> MAIN_QUEUES =
            List.of(RabbitTopology.INAPP_QUEUE, RabbitTopology.EMAIL_QUEUE);
    private static final List<RetryTtl> RETRY_TTLS = List.of(
            new RetryTtl("10s", 10_000),
            new RetryTtl("30s", 30_000),
            new RetryTtl("120s", 120_000));
    private static final List<QueueExpectation> EXPECTED_QUEUES = queueExpectations();
    private static final List<BindingExpectation> EXPECTED_BINDINGS = bindingExpectations();

    private final HttpClient client;
    private final NotificationRabbitProperties properties;
    private final Duration retryDelay;
    private final Duration totalBudget;
    private final String baseUrl;
    private final String authorization;

    @Autowired
    public RabbitTopologyVerifier(ObjectProvider<RestClient.Builder> builders,
                                  NotificationRabbitProperties properties) {
        this(buildClient(), properties, RETRY_DELAY, TOTAL_BUDGET);
    }

    RabbitTopologyVerifier(HttpClient client, NotificationRabbitProperties properties,
                           Duration retryDelay) {
        this(client, properties, retryDelay, TOTAL_BUDGET);
    }

    RabbitTopologyVerifier(HttpClient client, NotificationRabbitProperties properties,
                           Duration retryDelay, Duration totalBudget) {
        this.client = client;
        this.properties = properties;
        this.retryDelay = retryDelay;
        this.totalBudget = totalBudget;
        this.baseUrl = requireManagementUrl(properties.getManagementUrl());
        this.authorization = "Basic " + HttpHeaders.encodeBasicAuth(
                properties.getManagementUsername(), properties.getManagementPassword(),
                StandardCharsets.ISO_8859_1);
    }

    /**
     * Boot 4 only auto-configures a {@link RestClient.Builder} when its HTTP
     * client module is on the classpath. Keep the public constructor compatible,
     * but use one shared JDK transport to cancel incomplete response bodies.
     */
    static HttpClient buildClient() {
        return MANAGEMENT_CLIENT;
    }

    // The delivery startup barrier owns runner ordering and listener activation.
    public void run(ApplicationArguments args) {
        long deadline = deadlineFromNow();
        TopologyVerificationException failure = new TopologyVerificationException(
                TopologyVerificationException.Code.BINDING);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                verify(deadline);
                checkDeadline(deadline);
                return;
            } catch (TopologyVerificationException e) {
                failure = e;
            }
            if (attempt == MAX_ATTEMPTS || Thread.currentThread().isInterrupted()) {
                break;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(retryDelay.toNanos(), remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw failure;
    }

    /**
     * Performs one verification attempt. Tests call this directly so they do
     * not pay the startup retry delay.
     */
    public void verify() {
        verify(deadlineFromNow());
    }

    /**
     * Shares the caller's deadline so a startup retry never outlives the
     * total budget. Package-private for tests.
     */
    void verify(long deadline) {
        checkDeadline(deadline);
        verifyBrokerVersion(deadline);
        verifyStreamQueueFeature(deadline);
        verifyQueues(deadline);
        verifyBindings(deadline);
        checkDeadline(deadline);
    }

    private long deadlineFromNow() {
        return System.nanoTime() + totalBudget.toNanos();
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) {
            throw new TopologyVerificationException(
                    TopologyVerificationException.Code.VERIFICATION_TIMEOUT);
        }
    }

    private void verifyBrokerVersion(long deadline) {
        Map<String, Object> overview;
        try {
            overview = get(deadline, "/api/overview",
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException e) {
            checkDeadline(deadline);
            throw new TopologyVerificationException(
                    TopologyVerificationException.Code.BROKER_VERSION);
        }
        String expectedPrefix = properties.getExpectedVersionPrefix();
        if (expectedPrefix == null || expectedPrefix.isBlank()) {
            return;
        }
        Object version = overview == null ? null : overview.get("rabbitmq_version");
        if (version == null || !version.toString().startsWith(expectedPrefix)) {
            throw new TopologyVerificationException(
                    TopologyVerificationException.Code.BROKER_VERSION);
        }
    }

    private void verifyStreamQueueFeature(long deadline) {
        List<Map<String, Object>> flags;
        try {
            flags = get(deadline, "/api/feature-flags",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    });
        } catch (RestClientException e) {
            checkDeadline(deadline);
            throw new TopologyVerificationException(
                    TopologyVerificationException.Code.FEATURE_FLAG);
        }
        boolean enabled = flags != null && flags.stream().anyMatch(flag ->
                "stream_queue".equals(flag.get("name"))
                        && "enabled".equals(flag.get("state")));
        if (!enabled) {
            throw new TopologyVerificationException(
                    TopologyVerificationException.Code.FEATURE_FLAG);
        }
    }

    private void verifyQueues(long deadline) {
        for (QueueExpectation expectation : EXPECTED_QUEUES) {
            Map<String, Object> queue;
            try {
                queue = get(deadline, queuePath(expectation.name()),
                        new ParameterizedTypeReference<Map<String, Object>>() {
                        });
            } catch (RestClientException e) {
                checkDeadline(deadline);
                throw new TopologyVerificationException(
                        TopologyVerificationException.Code.QUEUE_POLICY, expectation.name());
            }
            if (!matches(queue, expectation)) {
                throw new TopologyVerificationException(
                        TopologyVerificationException.Code.QUEUE_POLICY, expectation.name());
            }
        }
    }

    private void verifyBindings(long deadline) {
        List<Map<String, Object>> bindings;
        try {
            bindings = get(deadline, "/api/bindings",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    });
        } catch (RestClientException e) {
            checkDeadline(deadline);
            throw new TopologyVerificationException(
                    TopologyVerificationException.Code.BINDING);
        }
        Set<String> actual = bindings == null ? Set.of() : bindings.stream()
                .filter(binding -> "/".equals(binding.get("vhost")))
                .map(binding -> bindingKey(
                        String.valueOf(binding.get("source")),
                        String.valueOf(binding.get("destination")),
                        String.valueOf(binding.get("routing_key"))))
                .collect(Collectors.toSet());
        for (BindingExpectation expectation : EXPECTED_BINDINGS) {
            if (!actual.contains(expectation.key())) {
                throw new TopologyVerificationException(
                        TopologyVerificationException.Code.BINDING);
            }
        }
    }

    private <T> T get(long deadline, String path, ParameterizedTypeReference<T> type) {
        checkDeadline(deadline);
        long now = System.nanoTime();
        long requestBudget = Math.min(READ_TIMEOUT.toNanos(), deadline - now);
        if (requestBudget <= 0) {
            checkDeadline(deadline);
        }
        long requestDeadline = now + requestBudget;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .timeout(Duration.ofNanos(requestBudget))
                .GET().build();
        CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(
                request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> received = response.get(
                    requestDeadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            checkDeadline(deadline);
            if (received.statusCode() >= 400) {
                throw new RestClientException("Management request failed");
            }
            T body = JSON.readValue(received.body(), JSON.constructType(type.getType()));
            checkDeadline(deadline);
            return body;
        } catch (TimeoutException e) {
            checkDeadline(deadline);
            throw new RestClientException("Management request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestClientException("Management request interrupted");
        } catch (ExecutionException | JacksonException e) {
            checkDeadline(deadline);
            throw new RestClientException("Management request failed");
        } finally {
            if (!response.isDone()) {
                response.cancel(true);
            }
        }
    }

    private static String queuePath(String queue) {
        return "/api/queues/%2F/"
                + UriUtils.encodePathSegment(queue, StandardCharsets.UTF_8);
    }

    private static boolean matches(Map<String, Object> payload,
                                   QueueExpectation expectation) {
        if (payload == null) {
            return false;
        }
        if (!"quorum".equals(payload.get("type"))) {
            return false;
        }
        if (!Boolean.TRUE.equals(payload.get("durable"))) {
            return false;
        }
        if (!Boolean.FALSE.equals(payload.get("auto_delete"))) {
            return false;
        }

        Map<String, Object> arguments = effectiveArguments(payload);
        if (arguments.containsKey("expires")) {
            return false;
        }
        if (expectation.messageTtlMs() == null) {
            if (arguments.containsKey("message-ttl")) {
                return false;
            }
        } else if (!matchesLong(arguments.get("message-ttl"), expectation.messageTtlMs())) {
            return false;
        }

        if (expectation.deadLetterExchange() != null) {
            if (!"at-least-once".equals(arguments.get("dead-letter-strategy"))) {
                return false;
            }
            if (!"reject-publish".equals(arguments.get("overflow"))) {
                return false;
            }
            if (!expectation.deadLetterExchange()
                    .equals(arguments.get("dead-letter-exchange"))) {
                return false;
            }
            if (!expectation.deadLetterRoutingKey()
                    .equals(arguments.get("dead-letter-routing-key"))) {
                return false;
            }
        }
        return !expectation.requiresDeliveryLimit()
                || matchesLong(arguments.get("delivery-limit"),
                RabbitTopologyConfig.DELIVERY_LIMIT);
    }

    /**
     * Queue arguments use {@code x-} names, policy definitions do not. The
     * effective value is the queue argument overridden by the applied policy.
     */
    private static Map<String, Object> effectiveArguments(Map<String, Object> payload) {
        Map<String, Object> merged = new LinkedHashMap<>();
        mergeDefinition(merged, payload.get("arguments"));
        mergeDefinition(merged, payload.get("effective_policy_definition"));
        return merged;
    }

    private static void mergeDefinition(Map<String, Object> target, Object definition) {
        if (!(definition instanceof Map<?, ?> map)) {
            return;
        }
        map.forEach((key, value) -> {
            String name = String.valueOf(key);
            target.put(name.startsWith("x-") ? name.substring(2) : name, value);
        });
    }

    private static boolean matchesLong(Object value, long expected) {
        if (value instanceof Number number) {
            return number.longValue() == expected;
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text) == expected;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static String bindingKey(String source, String destination, String routingKey) {
        return source + "\u0000" + destination + "\u0000" + routingKey;
    }

    private static String requireManagementUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "app.notifications.rabbit.management-url must be configured");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static List<QueueExpectation> queueExpectations() {
        List<QueueExpectation> queues = new ArrayList<>();
        for (String mainQueue : MAIN_QUEUES) {
            queues.add(new QueueExpectation(mainQueue, RabbitTopology.DLX,
                    mainQueue + ".dlq", null, true));
            for (RetryTtl retry : RETRY_TTLS) {
                String name = mainQueue + ".retry." + retry.suffix();
                queues.add(new QueueExpectation(name, RabbitTopology.RETRY_EXCHANGE,
                        mainQueue, retry.ttlMs(), false));
            }
            queues.add(new QueueExpectation(mainQueue + ".dlq", null, null, null, false));
        }
        return List.copyOf(queues);
    }

    private static List<BindingExpectation> bindingExpectations() {
        List<BindingExpectation> bindings = new ArrayList<>();
        for (String mainQueue : MAIN_QUEUES) {
            bindings.add(new BindingExpectation(RabbitTopology.EXCHANGE, mainQueue,
                    RabbitTopology.ROUTING_PATTERN));
            bindings.add(new BindingExpectation(RabbitTopology.RETRY_EXCHANGE, mainQueue,
                    mainQueue));
            for (RetryTtl retry : RETRY_TTLS) {
                String name = mainQueue + ".retry." + retry.suffix();
                bindings.add(new BindingExpectation(RabbitTopology.RETRY_EXCHANGE, name, name));
            }
            String deadLetterQueue = mainQueue + ".dlq";
            bindings.add(new BindingExpectation(RabbitTopology.DLX, deadLetterQueue,
                    deadLetterQueue));
        }
        return List.copyOf(bindings);
    }

    private record RetryTtl(String suffix, int ttlMs) {
    }

    private record QueueExpectation(String name, String deadLetterExchange,
                                    String deadLetterRoutingKey, Integer messageTtlMs,
                                    boolean requiresDeliveryLimit) {
    }

    private record BindingExpectation(String source, String destination, String routingKey) {

        private String key() {
            return bindingKey(source, destination, routingKey);
        }
    }
}
