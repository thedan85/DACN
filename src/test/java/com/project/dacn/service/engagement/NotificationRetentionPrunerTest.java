package com.project.dacn.service.engagement;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static com.project.dacn.service.engagement.NotificationRetentionTelemetry.TABLE_EMAIL;
import static com.project.dacn.service.engagement.NotificationRetentionTelemetry.TABLE_NOTIFICATIONS;
import static com.project.dacn.service.engagement.NotificationRetentionTelemetry.TABLE_OUTBOX;
import static com.project.dacn.service.engagement.NotificationRetentionTelemetry.TABLE_RECEIPTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRetentionPrunerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationRetentionTelemetry telemetry = mock(NotificationRetentionTelemetry.class);

    private NotificationRetentionPruner pruner() {
        return new NotificationRetentionPruner(jdbc, telemetry);
    }

    @Test
    void prunesFourTablesInBoundedBatchesWithStatementTimeout() throws SQLException {
        pruner().prune();

        ArgumentCaptor<PreparedStatementSetter> parameters = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc).update(eq(NotificationRetentionPruner.DELETE_NOTIFICATIONS), parameters.capture());
        verify(jdbc).update(eq(NotificationRetentionPruner.DELETE_OUTBOX), parameters.capture());
        verify(jdbc).update(eq(NotificationRetentionPruner.DELETE_EMAIL), parameters.capture());
        verify(jdbc).update(eq(NotificationRetentionPruner.DELETE_RECEIPTS), parameters.capture());
        assertThat(parameters.getAllValues()).hasSize(4);
        for (PreparedStatementSetter setter : parameters.getAllValues()) {
            PreparedStatement statement = mock(PreparedStatement.class);
            setter.setValues(statement);
            verify(statement).setInt(1, 1000);
            verify(statement).setQueryTimeout(2);
        }
        verify(telemetry).recordDeleted(TABLE_NOTIFICATIONS, 0);
        verify(telemetry).recordDeleted(TABLE_OUTBOX, 0);
        verify(telemetry).recordDeleted(TABLE_EMAIL, 0);
        verify(telemetry).recordDeleted(TABLE_RECEIPTS, 0);
    }

    @Test
    void batchSizeIsClampedTo1Through10000() throws SQLException {
        NotificationRetentionPruner high = pruner();
        ReflectionTestUtils.setField(high, "batchSize", 50_000);
        high.prune();
        ArgumentCaptor<PreparedStatementSetter> highParams = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc, org.mockito.Mockito.times(4)).update(any(String.class), highParams.capture());
        for (PreparedStatementSetter setter : highParams.getAllValues()) {
            PreparedStatement statement = mock(PreparedStatement.class);
            setter.setValues(statement);
            verify(statement).setInt(1, 10_000);
            verify(statement).setQueryTimeout(2);
        }

        JdbcTemplate lowJdbc = mock(JdbcTemplate.class);
        NotificationRetentionPruner low = new NotificationRetentionPruner(lowJdbc, telemetry);
        ReflectionTestUtils.setField(low, "batchSize", 0);
        low.prune();
        ArgumentCaptor<PreparedStatementSetter> lowParams = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(lowJdbc, org.mockito.Mockito.times(4)).update(any(String.class), lowParams.capture());
        for (PreparedStatementSetter setter : lowParams.getAllValues()) {
            PreparedStatement statement = mock(PreparedStatement.class);
            setter.setValues(statement);
            verify(statement).setInt(1, 1);
        }
    }

    @Test
    void boundedSqlUsesCorrectIntervalsAndProtectedPredicates() {
        List<String> statements = List.of(
                NotificationRetentionPruner.DELETE_NOTIFICATIONS,
                NotificationRetentionPruner.DELETE_OUTBOX,
                NotificationRetentionPruner.DELETE_EMAIL,
                NotificationRetentionPruner.DELETE_RECEIPTS);
        for (String sql : statements) {
            assertThat(sql).contains("limit ? for update skip locked");
            assertThat(sql).contains("delete from");
        }

        String notifications = NotificationRetentionPruner.DELETE_NOTIFICATIONS;
        assertThat(notifications).contains("delete from notifications");
        assertThat(notifications).contains("coalesce(occurred_at, created_at) <= clock_timestamp() - interval '90 days'");
        assertThat(notifications).doesNotContain("is_deleted", "notification_status");

        String outbox = NotificationRetentionPruner.DELETE_OUTBOX;
        assertThat(outbox).contains("delete from event_outbox");
        assertThat(outbox).contains("published_at <= clock_timestamp() - interval '30 days'");
        assertThat(outbox).contains("quarantined_at is null");
        assertThat(outbox).doesNotContain("published_at is null");

        String email = NotificationRetentionPruner.DELETE_EMAIL;
        assertThat(email).contains("delete from email_deliveries");
        assertThat(email).contains("state in ('SENT', 'SKIPPED')");
        assertThat(email).contains("completed_at <= clock_timestamp() - interval '90 days'");
        assertThat(email).doesNotContain("UNKNOWN", "FAILED", "PENDING", "IN_FLIGHT");

        String receipts = NotificationRetentionPruner.DELETE_RECEIPTS;
        assertThat(receipts).contains("delete from notification_event_receipts");
        assertThat(receipts).contains("completed_at <= clock_timestamp() - interval '90 days'");

        // Receipts are deleted only by their own completed-evidence statement; never by cascade.
        assertThat(notifications + outbox + email).doesNotContain("delete from notification_event_receipts");
        assertThat(statements).allSatisfy(sql -> assertThat(sql.toLowerCase()).doesNotContain("cascade"));
    }

    @Test
    void retentionRunsWhileDeliveryDisabledAndIsDisabledByRetentionFlag() {
        new ApplicationContextRunner()
                .withUserConfiguration(NotificationRetentionPruner.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(NotificationRetentionTelemetry.class, () -> telemetry)
                .withPropertyValues("app.notifications.delivery-enabled=false",
                        "app.notifications.retention-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(NotificationRetentionPruner.class));

        new ApplicationContextRunner()
                .withUserConfiguration(NotificationRetentionPruner.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(NotificationRetentionTelemetry.class, () -> telemetry)
                .withPropertyValues("app.notifications.retention-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NotificationRetentionPruner.class));
    }

    @Test
    void retentionDefaultsToEnabledWithoutTheDeliveryFlag() {
        new ApplicationContextRunner()
                .withUserConfiguration(NotificationRetentionPruner.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(NotificationRetentionTelemetry.class, () -> telemetry)
                .withPropertyValues("app.notifications.delivery-enabled=false")
                .run(context -> assertThat(context).hasSingleBean(NotificationRetentionPruner.class));
    }

    @Test
    void testProfileDisablesRetentionAgainstH2() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(NotificationRetentionPruner.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(NotificationRetentionTelemetry.class, () -> telemetry)
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).doesNotHaveBean(NotificationRetentionPruner.class));
    }

    @Test
    void failedBatchLogsOnlyABoundedCodeAndDoesNotStopIndependentBatches() {
        when(jdbc.update(eq(NotificationRetentionPruner.DELETE_NOTIFICATIONS), any(PreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("recipient@private.invalid event:private-id"));
        Logger logger = (Logger) LoggerFactory.getLogger(NotificationRetentionPruner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> pruner().prune()).doesNotThrowAnyException();
            verify(jdbc).update(eq(NotificationRetentionPruner.DELETE_OUTBOX), any(PreparedStatementSetter.class));
            verify(jdbc).update(eq(NotificationRetentionPruner.DELETE_EMAIL), any(PreparedStatementSetter.class));
            verify(jdbc).update(eq(NotificationRetentionPruner.DELETE_RECEIPTS), any(PreparedStatementSetter.class));
            verify(telemetry).recordFailure(TABLE_NOTIFICATIONS);
            assertThat(appender.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("code=RETENTION_BATCH_FAILED");
                assertThat(event.getFormattedMessage()).doesNotContain(
                        "recipient@private.invalid", "event:private-id", "private-id");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
