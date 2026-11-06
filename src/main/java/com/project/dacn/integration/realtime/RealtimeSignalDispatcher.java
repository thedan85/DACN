package com.project.dacn.integration.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.realtime.enabled", havingValue = "true")
public class RealtimeSignalDispatcher {

    private final JdbcTemplate jdbcTemplate;
    private final RealtimeSignalTelemetry telemetry;

    @Scheduled(fixedDelay = 250)
    public void dispatch() {
        try {
            jdbcTemplate.queryForObject(
                    "select app_private.dispatch_realtime_signals()", Integer.class);
            telemetry.dispatchSucceeded();
        } catch (RuntimeException exception) {
            // Bounded code only: no topic, user, or payload in the log.
            telemetry.dispatchFailed();
            log.warn("realtime dispatch failed code={}", exception.getClass().getSimpleName());
        }
    }
}
