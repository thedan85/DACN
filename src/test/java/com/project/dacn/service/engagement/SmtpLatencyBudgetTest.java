package com.project.dacn.service.engagement;

import com.project.dacn.event.OrderEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.project.dacn.common.constant.PaymentMethod.COD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 17 measured SMTP budget. Real order/payment/outbox commit, real relay poll,
 * real Rabbit queues, real EmailNotificationConsumer and loopback capture SMTP.
 * The stopwatch starts before the business transaction commit and ends at the
 * capture server's final DATA acceptance, both on the same JVM nanoTime base.
 */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("external-rabbit-dacn-topology")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmtpLatencyBudgetTest {

    static final int N = 30;

    private Task16JourneyHarness h;
    private final List<Sample> samples = new ArrayList<>();
    private long batchStart;
    private long batchEnd;

    @BeforeAll
    void start() throws Exception {
        h = Task16JourneyHarness.create();
        h.ownedTopology.purgeQueues();
    }

    @AfterAll
    void stop() throws Exception {
        if (h != null) {
            h.close();
        }
    }

    @Test
    void commitToFinalSmtpAcceptanceStaysWithinBudget() throws Exception {
        batchStart = System.nanoTime();
        for (int i = 0; i < N; i++) {
            String orderId = "ORD_t17_s" + i;
            long preCommit = System.nanoTime();
            OrderEvent event = h.placeOrderWithOutbox(orderId, COD); // real transaction commit
            long postCommit = System.nanoTime();
            long prePoll = System.nanoTime();
            h.relay.poll(); // real outbox relay -> real Rabbit queues
            long postPoll = System.nanoTime();
            boolean inapp = h.deliverInapp();
            boolean email = h.deliverEmail(); // real EmailNotificationConsumer

            int messageNo = i + 1;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (h.capture.messageCount() < messageNo && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            long accepted = h.capture.acceptanceNanosFor(messageNo);
            boolean unaccepted = accepted == Long.MIN_VALUE;
            String publishedAt = h.outboxRow(event.eventId(), "published_at");
            samples.add(new Sample(i, preCommit, postCommit, prePoll, postPoll,
                    unaccepted ? Long.MIN_VALUE : accepted, inapp, email, unaccepted,
                    publishedAt, event.eventId().toString()));
            h.ownedTopology.purgeQueues();
        }
        batchEnd = System.nanoTime();

        List<Double> upper = samples.stream().filter(s -> !s.unaccepted)
                .mapToDouble(s -> (s.accepted - s.preCommit) / 1e6).boxed().toList();
        long failures = samples.stream().filter(s -> !s.inapp || !s.email || s.unaccepted).count();
        double rate = N / ((batchEnd - batchStart) / 1e9);
        double p50 = percentile(upper, 0.50);
        double p95 = percentile(upper, 0.95);
        double p99 = percentile(upper, 0.99);
        double variance = variance(upper);

        Path evidence = writeJson(p50, p95, p99, variance, rate, failures);
        System.out.printf("TASK17_SMTP_BUDGET samples=%d accepted=%d p50=%.1fms p95=%.1fms p99=%.1fms "
                        + "rate=%.2forders/s variance=%.1fms2 failures=%d unaccepted=%d%n",
                N, upper.size(), p50, p95, p99, rate, variance, failures, N - upper.size());
        System.out.println("TASK17_SMTP_EVIDENCE_FILE=" + evidence);

        assertThat(upper).as("accepted SMTP samples").hasSize(N);
        assertThat(failures).as("unfinished SMTP samples").isZero();
        assertThat(p95).as("SMTP p95 upper bound (pre-commit -> final DATA acceptance), ms")
                .isLessThanOrEqualTo(10_000.0);
    }

    private Path writeJson(double p50, double p95, double p99, double variance,
                           double rate, long failures) throws IOException {
        String dir = System.getenv("TASK17_EVIDENCE_DIR");
        Path path = (dir == null || dir.isBlank())
                ? Paths.get("target", "task-17-evidence", "smtp-latency-samples.json")
                : Paths.get(dir, "smtp-latency-samples.json");
        Files.createDirectories(path.getParent());
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"timebase\": \"System.nanoTime, single JVM monotonic base\",\n");
        json.append("  \"definition\": \"upper bound = final DATA acceptance - preCommit; "
                + "commit bracket = postCommit - preCommit\",\n");
        json.append("  \"workload\": \"N fresh COD orders, one real commit each, real relay/Rabbit/"
                + "EmailNotificationConsumer/loopback SMTP, sequential\",\n");
        json.append("  \"summary\": {")
                .append("\"samples\":").append(N)
                .append(",\"accepted\":").append(samples.stream().filter(s -> !s.unaccepted).count())
                .append(",\"p50Ms\":").append(p50)
                .append(",\"p95Ms\":").append(p95)
                .append(",\"p99Ms\":").append(p99)
                .append(",\"varianceMs2\":").append(variance)
                .append(",\"achievedOrdersPerSec\":").append(rate)
                .append(",\"failures\":").append(failures)
                .append(",\"unaccepted\":").append(N - samples.stream().filter(s -> !s.unaccepted).count())
                .append(",\"targetP95Ms\":10000},\n");
        json.append("  \"samples\": [\n");
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            json.append("    {").append("\"i\":").append(s.i)
                    .append(",\"eventId\":\"").append(s.eventId).append("\"")
                    .append(",\"preCommitNanos\":").append(s.preCommit)
                    .append(",\"postCommitNanos\":").append(s.postCommit)
                    .append(",\"prePollNanos\":").append(s.prePoll)
                    .append(",\"postPollNanos\":").append(s.postPoll)
                    .append(",\"acceptanceNanos\":").append(s.accepted)
                    .append(",\"upperBoundMs\":").append(s.accepted == Long.MIN_VALUE ? -1 : (s.accepted - s.preCommit) / 1e6)
                    .append(",\"commitBracketMs\":").append((s.postCommit - s.preCommit) / 1e6)
                    .append(",\"commitToPollStartMs\":").append((s.prePoll - s.preCommit) / 1e6)
                    .append(",\"publishUpperMs\":").append((s.postPoll - s.preCommit) / 1e6)
                    .append(",\"publishToAcceptLowerMs\":").append(s.accepted == Long.MIN_VALUE ? -1 : (s.accepted - s.postPoll) / 1e6)
                    .append(",\"publishedAtWallClock\":").append(s.publishedAt == null ? "null" : "\"" + s.publishedAt + "\"")
                    .append(",\"inappDelivered\":").append(s.inapp)
                    .append(",\"emailDelivered\":").append(s.email)
                    .append(",\"unaccepted\":").append(s.unaccepted)
                    .append("}").append(i + 1 < samples.size() ? "," : "").append("\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
        return path;
    }

    /** Nearest-rank percentile over an unsorted list of milliseconds. */
    private static double percentile(List<Double> values, double fraction) {
        assertThat(values).isNotEmpty();
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int rank = (int) Math.ceil(fraction * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static double variance(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / values.size();
    }

    private record Sample(int i, long preCommit, long postCommit, long prePoll, long postPoll,
                          long accepted, boolean inapp, boolean email, boolean unaccepted,
                          String publishedAt, String eventId) { }
}
