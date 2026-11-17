package com.project.dacn.service.engagement;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded retention telemetry. Every tag value is a fixed constant: no event,
 * user, recipient, topic, order id, or SQL parameter ever reaches a metric.
 */
@Component
public class NotificationRetentionTelemetry {

    static final String DELETED = "dacn.retention.deleted";
    static final String FAILURE = "dacn.retention.failure";
    static final String BACKLOG = "dacn.retention.backlog";
    static final String EMAIL_UNRESOLVED = "dacn.retention.email.unresolved";
    static final String OUTBOX_QUARANTINED = "dacn.retention.outbox.quarantined";
    static final String OUTBOX_OLDEST_UNPUBLISHED_AGE = "dacn.retention.outbox.unpublished.oldest.age";

    static final String TABLE_NOTIFICATIONS = "notifications";
    static final String TABLE_OUTBOX = "event_outbox";
    static final String TABLE_EMAIL = "email_deliveries";
    static final String TABLE_RECEIPTS = "notification_event_receipts";

    private static final String TAG_TABLE = "table";
    private static final String TAG_CHANNEL = "channel";
    private static final String CHANNEL_EMAIL = "email";
    private static final List<String> TABLES = List.of(
            TABLE_NOTIFICATIONS, TABLE_OUTBOX, TABLE_EMAIL, TABLE_RECEIPTS);

    private static final Map<String, String> BACKLOG_SQL = Map.of(
            TABLE_NOTIFICATIONS, """
                    select count(*) from notifications
                     where coalesce(occurred_at, created_at) <= clock_timestamp() - interval '90 days'
                    """,
            TABLE_OUTBOX, """
                    select count(*) from event_outbox
                     where published_at <= clock_timestamp() - interval '30 days'
                       and quarantined_at is null
                    """,
            TABLE_EMAIL, """
                    select count(*) from email_deliveries
                     where state in ('SENT', 'SKIPPED')
                       and completed_at <= clock_timestamp() - interval '90 days'
                    """,
            TABLE_RECEIPTS, """
                    select count(*) from notification_event_receipts
                     where completed_at <= clock_timestamp() - interval '90 days'
                    """);

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, Counter> deletedCounters = new LinkedHashMap<>();
    private final Map<String, Counter> failureCounters = new LinkedHashMap<>();

    public NotificationRetentionTelemetry(MeterRegistry registry, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        for (String table : TABLES) {
            deletedCounters.put(table, Counter.builder(DELETED)
                    .description("Rows removed by bounded notification retention")
                    .tag(TAG_TABLE, table)
                    .register(registry));
            failureCounters.put(table, Counter.builder(FAILURE)
                    .description("Failed notification retention batches by fixed table")
                    .tag(TAG_TABLE, table)
                    .register(registry));
            String category = table;
            Gauge.builder(BACKLOG, this, source -> source.backlog(category))
                    .description("Eligible rows still awaiting bounded retention pruning")
                    .tag(TAG_TABLE, table)
                    .register(registry);
        }
        Gauge.builder(EMAIL_UNRESOLVED, this, NotificationRetentionTelemetry::unresolvedEmail)
                .description("Unresolved email deliveries awaiting review")
                .tag(TAG_CHANNEL, CHANNEL_EMAIL)
                .register(registry);
        Gauge.builder(OUTBOX_QUARANTINED, this, NotificationRetentionTelemetry::quarantinedOutbox)
                .description("Quarantined outbox rows awaiting review")
                .tag(TAG_TABLE, TABLE_OUTBOX)
                .register(registry);
        Gauge.builder(OUTBOX_OLDEST_UNPUBLISHED_AGE, this,
                        NotificationRetentionTelemetry::oldestUnpublishedOutboxAgeSeconds)
                .description("Age in seconds of the oldest unpublished, unquarantined outbox row")
                .baseUnit("seconds")
                .tag(TAG_TABLE, TABLE_OUTBOX)
                .register(registry);
    }

    public void recordDeleted(String table, int rows) {
        Counter counter = deletedCounters.get(table);
        if (counter != null && rows > 0) {
            counter.increment(rows);
        }
    }

    public void recordFailure(String table) {
        Counter counter = failureCounters.get(table);
        if (counter != null) {
            counter.increment();
        }
    }

    private double backlog(String table) {
        return count(BACKLOG_SQL.get(table));
    }

    private double unresolvedEmail() {
        return count("select count(*) from email_deliveries where state in ('UNKNOWN', 'FAILED')");
    }

    private double quarantinedOutbox() {
        return count("select count(*) from event_outbox where quarantined_at is not null");
    }

    private double oldestUnpublishedOutboxAgeSeconds() {
        return count("""
                select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
                  from event_outbox
                 where published_at is null and quarantined_at is null
                """);
    }

    // A scrape during migration or on H2 must never fail because a table is absent.
    private double count(String sql) {
        try {
            Double value = jdbcTemplate.queryForObject(sql, Double.class);
            return value == null ? 0 : Math.max(0, value);
        } catch (RuntimeException exception) {
            return 0;
        }
    }
}
