package com.project.dacn.service.engagement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.project.dacn.service.engagement.NotificationRetentionTelemetry.TABLE_EMAIL;
import static com.project.dacn.service.engagement.NotificationRetentionTelemetry.TABLE_NOTIFICATIONS;
import static com.project.dacn.service.engagement.NotificationRetentionTelemetry.TABLE_OUTBOX;
import static com.project.dacn.service.engagement.NotificationRetentionTelemetry.TABLE_RECEIPTS;

/** Bounded, independent cleanup. Dedup evidence is never a cascade target. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.notifications.retention-enabled", havingValue = "true",
        matchIfMissing = true)
public class NotificationRetentionPruner {

    static final String DELETE_NOTIFICATIONS = """
            delete from notifications
             where id in (select id from notifications
                           where coalesce(occurred_at, created_at) <= clock_timestamp() - interval '90 days'
                           order by coalesce(occurred_at, created_at), id
                           limit ? for update skip locked)
            """;
    static final String DELETE_OUTBOX = """
            delete from event_outbox
             where id in (select id from event_outbox
                           where published_at <= clock_timestamp() - interval '30 days'
                             and quarantined_at is null
                           order by published_at, id
                           limit ? for update skip locked)
            """;
    static final String DELETE_EMAIL = """
            delete from email_deliveries
             where id in (select id from email_deliveries
                           where state in ('SENT', 'SKIPPED')
                             and completed_at <= clock_timestamp() - interval '90 days'
                           order by completed_at, id
                           limit ? for update skip locked)
            """;
    static final String DELETE_RECEIPTS = """
            delete from notification_event_receipts
             where id in (select id from notification_event_receipts
                           where completed_at <= clock_timestamp() - interval '90 days'
                           order by completed_at, id
                           limit ? for update skip locked)
            """;

    @Value("${app.notifications.retention-batch-size:1000}")
    private int batchSize = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final NotificationRetentionTelemetry telemetry;

    @Scheduled(fixedDelayString = "${app.notifications.retention-interval-ms:600000}")
    public void prune() {
        int size = Math.max(1, Math.min(batchSize, 10_000));
        deleteBatch(DELETE_NOTIFICATIONS, TABLE_NOTIFICATIONS, size);
        deleteBatch(DELETE_OUTBOX, TABLE_OUTBOX, size);
        deleteBatch(DELETE_EMAIL, TABLE_EMAIL, size);
        deleteBatch(DELETE_RECEIPTS, TABLE_RECEIPTS, size);
    }

    private void deleteBatch(String sql, String table, int size) {
        try {
            int deleted = jdbcTemplate.update(sql, (PreparedStatementSetter) statement -> {
                statement.setInt(1, size);
                // A table lock must not monopolize the shared scheduler thread.
                statement.setQueryTimeout(2);
            });
            telemetry.recordDeleted(table, deleted);
        } catch (DataAccessException exception) {
            telemetry.recordFailure(table);
            log.warn("notification retention failed code=RETENTION_BATCH_FAILED table={}", table);
        }
    }
}
