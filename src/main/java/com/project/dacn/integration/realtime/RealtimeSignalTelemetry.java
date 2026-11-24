package com.project.dacn.integration.realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RealtimeSignalTelemetry {

    private final MeterRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    public void dispatchSucceeded() {
        registry.counter("dacn.realtime.dispatch.success").increment();
    }

    public void dispatchFailed() {
        registry.counter("dacn.realtime.dispatch.failure").increment();
    }

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("dacn.realtime.hints.pending", this,
                        RealtimeSignalTelemetry::pendingHints)
                .register(registry);
        Gauge.builder("dacn.realtime.hints.oldest.age", this,
                        RealtimeSignalTelemetry::oldestPendingAgeSeconds)
                .register(registry);
        Gauge.builder("dacn.realtime.leases.unretired", this,
                        RealtimeSignalTelemetry::unretiredLeases)
                .register(registry);
    }

    private double pendingHints() {
        return count("select count(*) from realtime_signal_outbox where sent_at is null");
    }

    private double oldestPendingAgeSeconds() {
        return count("""
                select coalesce(extract(epoch from clock_timestamp() - min(created_at)), 0)
                  from realtime_signal_outbox
                 where sent_at is null
                """);
    }

    private double unretiredLeases() {
        return count("select count(*) from realtime_token_leases where retire_at > clock_timestamp()");
    }

    private double count(String sql) {
        try {
            Double value = jdbcTemplate.queryForObject(sql, Double.class);
            return value == null ? 0 : value;
        } catch (RuntimeException exception) {
            // A missing table or a scrape during migration must not break the scrape.
            return 0;
        }
    }
}
