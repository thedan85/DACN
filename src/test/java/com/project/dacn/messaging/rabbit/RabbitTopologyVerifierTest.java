package com.project.dacn.messaging.rabbit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private static final String BINDINGS_URL = MANAGEMENT_URL + "/api/bindings/%2F";
    private static final String RABBIT_PROPERTIES = "app.notifications.rabbit";
    private static final String CLOUD_MODE = "RABBITMQ_CLOUDAMQP_LITTLE_LEMUR";
    private static final String FORBIDDEN_CHECKS = "RABBITMQ_CLOUD_ALLOW_FORBIDDEN_CHECKS";
    private static final String IDLE_EXPIRY = "RABBITMQ_EXPECTED_QUEUE_IDLE_EXPIRY";
    private static final long EXPIRY_28_DAYS_MS = 2_419_200_000L;
    private static final Map<String, Object> CLOUD = Map.of(CLOUD_MODE, "true");
    private static final Map<String, Object> CLOUD_WITH_FLAG_EXCEPTION =
            Map.of(CLOUD_MODE, "true", FORBIDDEN_CHECKS, "feature-flags");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Collection<Declarable> DECLARATIONS =
            new RabbitTopologyConfig().notificationTopology().getDeclarables();
    private static final List<Queue> DECLARED_QUEUES = DECLARATIONS.stream()
            .filter(Queue.class::isInstance)
            .map(Queue.class::cast)
            .toList();

    private MockRestServiceServer server;
    private HttpClient fixtureClient;
    private NotificationRabbitProperties verifierProperties;
    private RabbitTopologyVerifier verifier;
    private ManagementApi api;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        verifierProperties = properties();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        // Keep the existing API fixtures while exercising the verifier's async
        // response path. Local HTTP tests use the production transport directly.
        RestClient responses = builder.build();
        fixtureClient = mock(HttpClient.class);
        when(fixtureClient.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            HttpResponse<byte[]> response = responses.get().uri(request.uri())
                    .headers(headers -> request.headers().map().forEach(headers::put))
                    .exchange((sent, received) -> {
                        int status = received.getStatusCode().value();
                        byte[] body = received.getBody().readAllBytes();
                        HttpResponse<byte[]> result = mock(HttpResponse.class);
                        when(result.statusCode()).thenReturn(status);
                        when(result.body()).thenReturn(body);
                        return result;
                    });
            return CompletableFuture.completedFuture(response);
        });
        verifier = new RabbitTopologyVerifier(fixtureClient, verifierProperties, "/", Duration.ZERO);
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
    void applicationYamlDefaultsAreStrict() throws IOException {
        Binder binder = applicationBinder(Map.of());

        assertThat(binder.bind("spring.rabbitmq.virtual-host", String.class).get()).isEqualTo("/");
        assertThat(binder.bind("spring.rabbitmq.ssl.enabled", Boolean.class).get()).isFalse();
        assertThat(binder.bind(RABBIT_PROPERTIES + ".expected-version-prefix", String.class).get())
                .isEqualTo("4.4");
        assertThat(binder.bind(RABBIT_PROPERTIES + ".cloudamqp-little-lemur", Boolean.class)
                .orElse(null)).isEqualTo(false);
        assertThat(binder.bind(RABBIT_PROPERTIES + ".cloud-allow-forbidden-checks",
                Bindable.setOf(String.class)).orElse(Set.of())).isEmpty();
        assertThat(binder.bind(RABBIT_PROPERTIES + ".expected-queue-idle-expiry", Duration.class)
                .isBound()).isFalse();
    }

    @Test
    void unknownAllowlistNameFailsPropertyBindingEvenOutsideCloudMode() {
        assertThatThrownBy(() -> configure(Map.of(FORBIDDEN_CHECKS, "feature-flags,queues")))
                .hasStackTraceContaining("Unknown RabbitMQ management check");
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.3.6", "4.3.27", "4.4.0", " 4.4.0 "})
    void cloudModeAcceptsStableVersionsAcrossBothSupportedSeries(String version) throws IOException {
        configure(CLOUD);
        api.version = version;
        api.stub();

        assertThatCode(verifier::verify).doesNotThrowAnyException();
        server.verify();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"4.2.9", "4.5.0", "4.30.1", "4.3", "4.3.6.1", "4.3.6-rc1",
            "v4.3.6", "4.4.0-rc1"})
    void cloudModeRejectsUnsupportedOrMissingVersions(String version) throws IOException {
        configure(CLOUD);
        api.version = version;
        api.stub();

        assertFailure(TopologyVerificationException.Code.BROKER_VERSION, null);
    }

    @Test
    void cloudModeRequiresReadableVersionEvenWithBlankLocalPrefix() throws IOException {
        configure(Map.of(CLOUD_MODE, "true", "RABBITMQ_EXPECTED_VERSION", ""));
        api.version = null;
        api.stub();

        assertFailure(TopologyVerificationException.Code.BROKER_VERSION, null);
    }

    @Test
    void localModeStillRejectsAStableCloudVersionOutsideItsPrefix() throws IOException {
        configure(Map.of());
        api.version = "4.3.6";
        api.stub();

        assertFailure(TopologyVerificationException.Code.BROKER_VERSION, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void queueIdleExpiryOptInAllowsExact28DaysWithoutRequiringIt(boolean hasExpiry)
            throws IOException {
        configure(Map.of(IDLE_EXPIRY, "28d"));
        if (hasExpiry) {
            api.setExpiry(RabbitTopology.INAPP_QUEUE, EXPIRY_28_DAYS_MS);
        }
        api.stub();

        assertThatCode(verifier::verify).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void unsetQueueIdleExpiryRejectsEvenThe28DayPolicy() throws IOException {
        configure(Map.of());
        api.setExpiry(RabbitTopology.INAPP_QUEUE, EXPIRY_28_DAYS_MS);
        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.INAPP_QUEUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"60000", "2419200000.5", "2419200000.0000001"})
    void queueIdleExpiryOptInRejectsNonmatchingNumericValues(String expiry) throws IOException {
        configure(Map.of(IDLE_EXPIRY, "28d"));
        api.setExpiry(RabbitTopology.INAPP_QUEUE, new BigDecimal(expiry));
        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.INAPP_QUEUE);
    }

    @Test
    void queueIdleExpiryOptInRejectsMalformedExpiry() throws IOException {
        configure(Map.of(IDLE_EXPIRY, "28d"));
        api.setExpiry(RabbitTopology.INAPP_QUEUE, "not-milliseconds");
        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, RabbitTopology.INAPP_QUEUE);
    }

    @ParameterizedTest
    @CsvSource({
            "dacn.notifications.inapp, x-message-ttl",
            "dacn.notifications.inapp.retry.30s, x-message-ttl",
            "dacn.notifications.inapp, x-overflow",
            "dacn.notifications.inapp, x-dead-letter-strategy"
    })
    void queueIdleExpiryOptInDoesNotRelaxOtherQueueChecks(String queue, String key)
            throws IOException {
        configure(Map.of(IDLE_EXPIRY, "28d"));
        api.mutateQueue(queue, payload -> {
            payload.put("effective_policy_definition", Map.of("expires", EXPIRY_28_DAYS_MS));
            arguments(payload).put(key, key.equals("x-message-ttl") ? 60_000 : "wrong");
        });
        api.stub();

        assertFailure(TopologyVerificationException.Code.QUEUE_POLICY, queue);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void skipsOnlyAllowlistedFeatureFlags403AndLogsOncePerVerifier(CapturedOutput output)
            throws IOException {
        configure(Map.of(CLOUD_MODE, "true", FORBIDDEN_CHECKS,
                "policies, feature-flags, operator-policies"));
        api.fail(FEATURE_FLAGS_URL, HttpStatus.FORBIDDEN);
        api.stub(ExpectedCount.twice());

        assertThatCode(verifier::verify).doesNotThrowAnyException();
        assertThatCode(verifier::verify).doesNotThrowAnyException();
        server.verify(); // No policy-list or other unexpected requests.
        assertThat(output.getAll().lines()
                .filter(line -> line.contains("/api/feature-flags") && line.contains("403")))
                .hasSize(1);
        assertThat(output.getAll()).doesNotContain("test error body", PASSWORD, MANAGEMENT_URL);
    }

    @ParameterizedTest
    @CsvSource({
            "false,feature-flags",
            "true,''",
            "true,policies",
            "true,operator-policies"
    })
    void featureFlags403FailsWithoutBothOptIns(boolean cloudMode, String allowedCheck)
            throws IOException {
        configure(Map.of(CLOUD_MODE, String.valueOf(cloudMode), FORBIDDEN_CHECKS, allowedCheck));
        api.fail(FEATURE_FLAGS_URL, HttpStatus.FORBIDDEN);
        api.stub();

        assertFailure(TopologyVerificationException.Code.FEATURE_FLAG, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 404, 500})
    void allowlistedFeatureFlagsStillRequireSuccessOr403(int status) throws IOException {
        configure(CLOUD_WITH_FLAG_EXCEPTION);
        api.fail(FEATURE_FLAGS_URL, HttpStatus.valueOf(status));
        api.errorBody = "[{\"name\":\"stream_queue\",\"state\":\"enabled\"}]";
        api.stub();

        assertFailure(TopologyVerificationException.Code.FEATURE_FLAG, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not-json", "[null]", "[null,{\"name\":\"stream_queue\",\"state\":\"enabled\"}]"})
    void allowlistedFeatureFlagsStillRejectMalformedBody(String body) throws IOException {
        configure(CLOUD_WITH_FLAG_EXCEPTION);
        api.flagsBody = body;
        api.stub();

        assertFailure(TopologyVerificationException.Code.FEATURE_FLAG, null);
    }

    @Test
    void allowlistedFeatureFlagsStillRejectDisabledFlagWithExpiryOptIn() throws IOException {
        configure(Map.of(CLOUD_MODE, "true", FORBIDDEN_CHECKS, "feature-flags", IDLE_EXPIRY, "28d"));
        api.streamQueueState = "disabled";
        api.stub();

        assertFailure(TopologyVerificationException.Code.FEATURE_FLAG, null);
    }

    @ParameterizedTest
    @CsvSource({
            OVERVIEW_URL + ", BROKER_VERSION",
            MANAGEMENT_URL + "/api/queues/%2F/" + RabbitTopology.INAPP_QUEUE + ", QUEUE_POLICY",
            BINDINGS_URL + ", BINDING"
    })
    void cloudOptInsDoNotPermit403OnMandatoryEndpoints(
            String url, TopologyVerificationException.Code code) throws IOException {
        configure(Map.of(CLOUD_MODE, "true", FORBIDDEN_CHECKS, "feature-flags,policies,operator-policies"));
        api.fail(url, HttpStatus.FORBIDDEN);
        api.stub();

        assertFailure(code, code == TopologyVerificationException.Code.QUEUE_POLICY
                ? RabbitTopology.INAPP_QUEUE : null);
    }

    @Test
    void skippedFeatureFlagsDoNotHideAnInvalidBinding() throws IOException {
        configure(CLOUD_WITH_FLAG_EXCEPTION);
        api.fail(FEATURE_FLAGS_URL, HttpStatus.FORBIDDEN);
        api.dropBinding(RabbitTopology.EXCHANGE, RabbitTopology.EMAIL_QUEUE,
                RabbitTopology.ROUTING_PATTERN);
        api.stub();

        assertFailure(TopologyVerificationException.Code.BINDING, null);
        server.verify();
    }

    @Test
    void allowlistedFeatureFlagsStillFailOnAnHttpTimeout() throws IOException {
        configure(CLOUD_WITH_FLAG_EXCEPTION);
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean flagsRequested = new AtomicBoolean();
        CountDownLatch releaseResponse = new CountDownLatch(1);
        local.createContext("/api/overview", exchange -> {
            try (exchange) {
                byte[] body = JSON.writeValueAsBytes(Map.of("rabbitmq_version", "4.4.2"));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        local.createContext("/api/feature-flags", exchange -> {
            try (exchange) {
                flagsRequested.set(true);
                releaseResponse.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        local.start();
        try {
            verifierProperties.setManagementUrl("http://127.0.0.1:" + local.getAddress().getPort());
            verifier = new RabbitTopologyVerifier(RabbitTopologyVerifier.buildClient(),
                    verifierProperties, "/", Duration.ZERO);

            assertFailure(TopologyVerificationException.Code.FEATURE_FLAG, null);
            assertThat(flagsRequested).isTrue();
        } finally {
            releaseResponse.countDown();
            local.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void verifiesEncodedManagementPathsAndRequiresBindingsInTheActiveVhost(boolean bindingsInVhost)
            throws IOException {
        String vhost = "course/dev %";
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.getVirtualHost()).thenReturn(vhost);
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/api/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getRawPath();
                paths.add(path);
                Object payload;
                if (path.equals("/api/overview")) {
                    payload = Map.of("rabbitmq_version", "4.4.2");
                } else if (path.equals("/api/feature-flags")) {
                    payload = List.of(Map.of("name", "stream_queue", "state", "enabled"));
                } else if (path.startsWith("/api/bindings")) {
                    payload = bindingPayload(bindingsInVhost ? vhost : "/");
                } else {
                    payload = DECLARED_QUEUES.stream()
                            .filter(queue -> path.endsWith("/" + queue.getName()))
                            .findFirst().map(RabbitTopologyVerifierTest::queuePayload).orElseThrow();
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] body = JSON.writeValueAsBytes(payload);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        local.start();
        try {
            NotificationRabbitProperties properties = properties();
            properties.setManagementUrl("http://127.0.0.1:" + local.getAddress().getPort());
            new ApplicationContextRunner()
                    .withPropertyValues("app.notifications.delivery-enabled=true")
                    .withBean(ConnectionFactory.class, () -> factory)
                    .withBean(NotificationRabbitProperties.class, () -> properties)
                    .withUserConfiguration(RabbitTopologyVerifier.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(RabbitTopologyVerifier.class);
                        var failure = catchThrowableOfType(
                                context.getBean(RabbitTopologyVerifier.class)::verify,
                                TopologyVerificationException.class);
                        assertSoftly(softly -> {
                            softly.assertThat(paths).filteredOn(path -> path.startsWith("/api/queues/"))
                                    .containsExactlyInAnyOrderElementsOf(DECLARED_QUEUES.stream()
                                            .map(queue -> "/api/queues/course%2Fdev%20%25/" + queue.getName())
                                            .toList());
                            softly.assertThat(paths).filteredOn(path -> path.startsWith("/api/bindings"))
                                    .containsExactly("/api/bindings/course%2Fdev%20%25");
                            if (bindingsInVhost) {
                                softly.assertThat(failure).isNull();
                            } else {
                                softly.assertThat(failure == null ? null : failure.code())
                                        .isEqualTo(TopologyVerificationException.Code.BINDING);
                            }
                        });
                        verify(factory, never()).createConnection();
                    });
        } finally {
            local.stop(0);
        }
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
                RabbitTopologyVerifier.buildClient(), properties(), "/", Duration.ZERO) {
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
                RabbitTopologyVerifier.buildClient(), properties(), "/", Duration.ZERO, Duration.ZERO);

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
                } else if (path.equals("/api/bindings/%2F")) {
                    finalResponseStarted.set(System.nanoTime() < deadline);
                    payload = bindingPayload("/");
                } else {
                    payload = DECLARED_QUEUES.stream()
                            .filter(queue -> path.equals("/api/queues/%2F/" + queue.getName()))
                            .findFirst().map(RabbitTopologyVerifierTest::queuePayload).orElseThrow();
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                byte[] body = JSON.writeValueAsBytes(payload);
                if (path.equals("/api/bindings/%2F")) {
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
                    properties, "/", Duration.ZERO);

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
                RabbitTopologyVerifier.buildClient(), properties(), "/", Duration.ZERO, Duration.ZERO) {
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
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.getVirtualHost()).thenReturn("/");

        new ApplicationContextRunner()
                .withPropertyValues("app.notifications.delivery-enabled=true")
                .withBean(ConnectionFactory.class, () -> factory)
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

    private void configure(Map<String, Object> variables) throws IOException {
        Map<String, Object> config = new HashMap<>(variables);
        config.put("RABBITMQ_MANAGEMENT_URL", MANAGEMENT_URL);
        config.put("RABBITMQ_MANAGEMENT_USERNAME", USERNAME);
        config.put("RABBITMQ_MANAGEMENT_PASSWORD", PASSWORD);
        verifierProperties = applicationBinder(config)
                .bind(RABBIT_PROPERTIES, NotificationRabbitProperties.class).get();
        verifier = new RabbitTopologyVerifier(fixtureClient, verifierProperties, "/", Duration.ZERO);
    }

    private static Binder applicationBinder(Map<String, Object> variables) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testVariables", variables));
        for (var source : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment);
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

    private static List<Map<String, Object>> bindingPayload(String vhost) {
        return DECLARATIONS.stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .map(binding -> Map.<String, Object>of(
                        "source", binding.getExchange(),
                        "destination", binding.getDestination(),
                        "destination_type", "queue",
                        "routing_key", binding.getRoutingKey(),
                        "vhost", vhost))
                .toList();
    }

    private static String bindingKey(String source, String destination, String routingKey) {
        return source + "|" + destination + "|" + routingKey;
    }

    private final class ManagementApi {

        private String version = "4.4.2";
        private boolean streamQueuePresent = true;
        private String streamQueueState = "enabled";
        private String flagsBody;
        private String errorBody = "test error body";
        private Runnable beforeBindings = () -> { };
        private final Set<String> droppedQueues = new HashSet<>();
        private final Set<String> droppedBindings = new HashSet<>();
        private final Map<String, HttpStatus> statuses = new HashMap<>();
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

        private void setExpiry(String queue, Object expiry) {
            mutateQueue(queue, payload ->
                    payload.put("effective_policy_definition", Map.of("expires", expiry)));
        }

        private void fail(String url, HttpStatus status) {
            statuses.put(url, status);
        }

        private void stub() {
            stub(ExpectedCount.once());
        }

        private void stub(ExpectedCount count) {
            server.expect(count, requestTo(OVERVIEW_URL))
                    .andExpect(header("Authorization", basicAuthorization()))
                    .andRespond(response(OVERVIEW_URL, withSuccess(
                            json(version == null ? Map.of() : Map.of("rabbitmq_version", version)),
                            MediaType.APPLICATION_JSON)));

            List<Map<String, Object>> flags = new ArrayList<>();
            if (streamQueuePresent) {
                flags.add(Map.of("name", "stream_queue", "state", streamQueueState));
            }
            flags.add(Map.of("name", "maintenance_mode", "state", "disabled"));
            server.expect(count, requestTo(FEATURE_FLAGS_URL))
                    .andRespond(response(FEATURE_FLAGS_URL, withSuccess(
                            flagsBody == null ? json(flags) : flagsBody, MediaType.APPLICATION_JSON)));

            for (Queue queue : DECLARED_QUEUES) {
                String url = queueUrl(queue.getName());
                if (droppedQueues.contains(queue.getName())) {
                    server.expect(count, requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND));
                    continue;
                }
                Map<String, Object> payload = queuePayload(queue);
                Consumer<Map<String, Object>> customizer = queueCustomizers.get(queue.getName());
                if (customizer != null) {
                    customizer.accept(payload);
                }
                server.expect(count, requestTo(url))
                        .andRespond(response(url, withSuccess(json(payload), MediaType.APPLICATION_JSON)));
            }

            List<Map<String, Object>> bindings = bindingPayload("/").stream()
                    .filter(binding -> !droppedBindings.contains(bindingKey(
                            (String) binding.get("source"),
                            (String) binding.get("destination"),
                            (String) binding.get("routing_key"))))
                    .toList();
            server.expect(count, requestTo(BINDINGS_URL))
                    .andRespond(response(BINDINGS_URL, request -> {
                        beforeBindings.run();
                        return withSuccess(json(bindings), MediaType.APPLICATION_JSON)
                                .createResponse(request);
                    }));
        }

        private ResponseCreator response(String url, ResponseCreator success) {
            HttpStatus status = statuses.get(url);
            return status == null ? success : withStatus(status).body(errorBody);
        }

        private String json(Object value) {
            return JSON.writeValueAsString(value);
        }
    }
}
