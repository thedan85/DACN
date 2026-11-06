package com.project.dacn.integration.realtime;

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

import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeRetentionPrunerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void prunesRetiredLeasesStaleHintsAndOldAccountingInBoundedBatches() throws SQLException {
        new RealtimeRetentionPruner(jdbc).prune();

        ArgumentCaptor<PreparedStatementSetter> parameters = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc).update(eq(RealtimeRetentionPruner.DELETE_RETIRED_LEASES), parameters.capture());
        verify(jdbc).update(eq(RealtimeRetentionPruner.DELETE_STALE_HINTS), parameters.capture());
        verify(jdbc).update(eq(RealtimeRetentionPruner.DELETE_OLD_ACCOUNTING), parameters.capture());
        for (PreparedStatementSetter setter : parameters.getAllValues()) {
            PreparedStatement statement = mock(PreparedStatement.class);
            setter.setValues(statement);
            verify(statement).setInt(1, 1000);
            verify(statement).setQueryTimeout(1);
        }
    }

    @Test
    void neverDeletesDurableEventsOrUnresolvedWithinWindow() {
        assertThat(RealtimeRetentionPruner.DELETE_RETIRED_LEASES).doesNotContain("event_outbox");
        assertThat(RealtimeRetentionPruner.DELETE_STALE_HINTS).doesNotContain("event_outbox");
        assertThat(RealtimeRetentionPruner.DELETE_OLD_ACCOUNTING).doesNotContain("event_outbox");
        assertThat(RealtimeRetentionPruner.DELETE_STALE_HINTS)
                .contains("interval '30 seconds'");
        assertThat(RealtimeRetentionPruner.DELETE_OLD_ACCOUNTING)
                .contains("interval '1 hour'");
        assertThat(RealtimeRetentionPruner.DELETE_RETIRED_LEASES).contains("retire_at <=");
    }

    @Test
    void retentionRunsEvenWhenRealtimeDeliveryIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(RealtimeRetentionPruner.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withPropertyValues("app.realtime.enabled=false",
                        "app.realtime.retention-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(RealtimeRetentionPruner.class));

        new ApplicationContextRunner()
                .withUserConfiguration(RealtimeRetentionPruner.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withPropertyValues("app.realtime.retention-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(RealtimeRetentionPruner.class));
    }

    @Test
    void retentionDefaultsToEnabledWithoutTheDeliveryFlag() {
        new ApplicationContextRunner()
                .withUserConfiguration(RealtimeRetentionPruner.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withPropertyValues("app.realtime.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(RealtimeRetentionPruner.class));
    }

    @Test
    void testProfileDisablesRetentionAgainstH2WithoutChangingProductionDefaults() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RealtimeRetentionPruner.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).doesNotHaveBean(RealtimeRetentionPruner.class));
    }

    @Test
    void failedBatchLogsOnlyABoundedCodeAndDoesNotStopIndependentBatches() {
        when(jdbc.update(eq(RealtimeRetentionPruner.DELETE_RETIRED_LEASES), any(PreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("inbox:private-user private-token"));
        Logger logger = (Logger) LoggerFactory.getLogger(RealtimeRetentionPruner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> new RealtimeRetentionPruner(jdbc).prune()).doesNotThrowAnyException();
            verify(jdbc).update(eq(RealtimeRetentionPruner.DELETE_STALE_HINTS), any(PreparedStatementSetter.class));
            verify(jdbc).update(eq(RealtimeRetentionPruner.DELETE_OLD_ACCOUNTING), any(PreparedStatementSetter.class));
            assertThat(appender.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).matches("realtime retention failed code=[A-Z_]+");
                assertThat(event.getFormattedMessage()).doesNotContain("private-user", "private-token", "inbox:");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
