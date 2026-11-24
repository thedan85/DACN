package com.project.dacn.messaging.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OutboxTelemetry {

    static final String PENDING_COUNT = "dacn.outbox.pending.count";
    static final String OLDEST_PENDING_AGE = "dacn.outbox.pending.oldest.age";

    private static final String UNFINISHED =
            "published_at is null and quarantined_at is null";

    private final JdbcTemplate jdbcTemplate;
    private final Counter publishSuccess;
    private final Counter publishFailure;
    private final Counter quarantine;
    private final Timer publishTimer;

    public OutboxTelemetry(MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.publishSuccess = Counter.builder("dacn.outbox.publish.success")
                .description("Order events published successfully")
                .register(meterRegistry);
        this.publishFailure = Counter.builder("dacn.outbox.publish.failure")
                .description("Order event publish attempts that failed")
                .register(meterRegistry);
        this.quarantine = Counter.builder("dacn.outbox.quarantine")
                .description("Order events quarantined by the relay")
                .register(meterRegistry);
        this.publishTimer = Timer.builder("dacn.outbox.publish")
                .description("Order event publish duration")
                .register(meterRegistry);

        // Gauges query the database lazily at scrape time and must never fail a scrape.
        Gauge.builder(PENDING_COUNT, this, OutboxTelemetry::pendingCount)
                .description("Unpublished, unquarantined order events")
                .register(meterRegistry);
        Gauge.builder(OLDEST_PENDING_AGE, this, OutboxTelemetry::oldestPendingAgeSeconds)
                .description("Age in seconds of the oldest unpublished order event")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    public void recordPublishSuccess(Duration elapsed) {
        publishSuccess.increment();
        publishTimer.record(elapsed);
    }

    public void recordPublishFailure(Duration elapsed) {
        publishFailure.increment();
        publishTimer.record(elapsed);
    }

    public void recordQuarantine() {
        quarantine.increment();
    }

    private double pendingCount() {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from event_outbox where " + UNFINISHED, Long.class);
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private double oldestPendingAgeSeconds() {
        try {
            Double age = jdbcTemplate.queryForObject("""
                    select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
                      from event_outbox
                     where %s
                    """.formatted(UNFINISHED), Double.class);
            return age == null ? 0 : Math.max(0, age);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
