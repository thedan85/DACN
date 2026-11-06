package com.project.dacn.integration.realtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeSignalDispatcherTest {

    private static final String CALL = "select app_private.dispatch_realtime_signals()";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RealtimeSignalTelemetry telemetry = mock(RealtimeSignalTelemetry.class);

    @Test
    void callsTheSqlFunctionOncePerTickAndRecordsSuccess() {
        when(jdbc.queryForObject(CALL, Integer.class)).thenReturn(2);

        new RealtimeSignalDispatcher(jdbc, telemetry).dispatch();

        verify(jdbc, times(1)).queryForObject(CALL, Integer.class);
        verify(telemetry).dispatchSucceeded();
    }

    @Test
    void databaseFailureIsContainedAndNotRetriedInline() {
        when(jdbc.queryForObject(CALL, Integer.class))
                .thenThrow(new DataAccessResourceFailureException("deadline exceeded"));

        assertThatCode(() -> new RealtimeSignalDispatcher(jdbc, telemetry).dispatch())
                .doesNotThrowAnyException();

        verify(jdbc, times(1)).queryForObject(CALL, Integer.class);
        verify(telemetry).dispatchFailed();
        verify(telemetry, times(0)).dispatchSucceeded();
    }

    @Test
    void beanExistsOnlyWhenRealtimeIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(RealtimeSignalDispatcher.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(RealtimeSignalTelemetry.class, () -> telemetry)
                .withPropertyValues("app.realtime.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RealtimeSignalDispatcher.class));

        new ApplicationContextRunner()
                .withUserConfiguration(RealtimeSignalDispatcher.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(RealtimeSignalTelemetry.class, () -> telemetry)
                .withPropertyValues("app.realtime.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RealtimeSignalDispatcher.class));
    }

    @Test
    void schedulesOneSqlInvocationEvery250MillisecondsWithoutAnInlineRetry() throws Exception {
        Scheduled schedule = RealtimeSignalDispatcher.class.getMethod("dispatch")
                .getAnnotation(Scheduled.class);

        assertThat(schedule.fixedDelay()).isEqualTo(250);
        assertThat(schedule.fixedRate()).isEqualTo(-1);
        new RealtimeSignalDispatcher(jdbc, telemetry).dispatch();
        verify(jdbc).queryForObject(CALL, Integer.class);
    }

    @Test
    void registersUntaggedGaugesAtStartupAndContainsScrapeFailures() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(RealtimeSignalTelemetry.class)
                    .withBean(MeterRegistry.class, () -> registry)
                    .withBean(JdbcTemplate.class, () -> jdbc)
                    .run(context -> {
                        assertThat(context).hasSingleBean(RealtimeSignalTelemetry.class);
                        verify(jdbc, times(0)).queryForObject(anyString(), eq(Double.class));
                        assertThat(registry.getMeters()).extracting(meter -> meter.getId().getName())
                                .containsExactlyInAnyOrder("dacn.realtime.hints.pending",
                                        "dacn.realtime.hints.oldest.age", "dacn.realtime.leases.unretired");
                        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(3.0);
                        registry.getMeters().forEach(meter -> {
                            assertThat(meter.getId().getTags()).isEmpty();
                            assertThat(registry.get(meter.getId().getName()).gauge().value()).isEqualTo(3.0);
                        });
                        when(jdbc.queryForObject(anyString(), eq(Double.class)))
                                .thenThrow(new DataAccessResourceFailureException("private database detail"));
                        registry.getMeters().forEach(meter ->
                                assertThat(registry.get(meter.getId().getName()).gauge().value()).isZero());
                    });
        } finally {
            registry.close();
        }
    }

    @Test
    void dispatchMetricsAndFailureLogsContainNoDatabaseDetailsOrDynamicLabels() {
        when(jdbc.queryForObject(CALL, Integer.class)).thenReturn(2)
                .thenThrow(new DataAccessResourceFailureException("inbox:private-user private-token"));
        Logger logger = (Logger) LoggerFactory.getLogger(RealtimeSignalDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            RealtimeSignalDispatcher dispatcher = new RealtimeSignalDispatcher(jdbc,
                    new RealtimeSignalTelemetry(registry, jdbc));
            dispatcher.dispatch();
            dispatcher.dispatch();

            assertThat(registry.get("dacn.realtime.dispatch.success").counter().count()).isEqualTo(1);
            assertThat(registry.get("dacn.realtime.dispatch.failure").counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).isEmpty());
            assertThat(appender.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).matches("realtime dispatch failed code=[A-Za-z_]+");
                assertThat(event.getFormattedMessage()).doesNotContain("private-user", "private-token", "inbox:");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            registry.close();
        }
    }
}
