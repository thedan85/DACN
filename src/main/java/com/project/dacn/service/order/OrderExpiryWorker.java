package com.project.dacn.service.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded owner of scheduled expiry claims; the lifecycle owns each transaction. */
@Slf4j
@Component
public class OrderExpiryWorker {

    public static final String CLAIMS_METRIC = "dacn.inventory.expiry.claims";
    public static final String FAILURE_METRIC = "dacn.inventory.expiry.failures";
    public static final String TICK_METRIC = "dacn.inventory.expiry.tick";

    static final int MAX_CLAIMS_PER_TICK = 50;

    private final OrderLifecycleService lifecycle;
    private ReservationPolicy reservationPolicy;
    private final MeterRegistry meterRegistry;
    private final Counter claims;
    private final Counter failures;
    private final Timer tick;

    public OrderExpiryWorker(OrderLifecycleService lifecycle,
                             ReservationPolicy reservationPolicy,
                             MeterRegistry meterRegistry) {
        this.lifecycle = lifecycle;
        this.reservationPolicy = reservationPolicy;
        this.meterRegistry = meterRegistry;
        this.claims = Counter.builder(CLAIMS_METRIC)
                .description("Due orders claimed by the expiry worker")
                .register(meterRegistry);
        this.failures = Counter.builder(FAILURE_METRIC)
                .description("Expiry claims that failed and rolled back")
                .register(meterRegistry);
        this.tick = Timer.builder(TICK_METRIC)
                .description("Scheduled expiry tick duration")
                .register(meterRegistry);
    }

    /**
     * One tick performs at most {@value #MAX_CLAIMS_PER_TICK} independent claim
     * transactions. The guard is rechecked before every claim; online pause
     * does not stop sweeping. Failures stop the tick and are never counted as claims.
     */
    @Scheduled(fixedDelay = 5_000)
    public void sweep() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            for (int i = 0; i < MAX_CLAIMS_PER_TICK; i++) {
                if (!reservationPolicy.isActive() || reservationPolicy.isSweepingPaused()) {
                    return;
                }
                try {
                    if (!lifecycle.expireNextDue()) {
                        return;
                    }
                    claims.increment();
                } catch (RuntimeException error) {
                    log.warn("Expiry claim failed with {}", error.getClass().getSimpleName());
                    failures.increment();
                    return;
                }
            }
        } finally {
            sample.stop(tick);
        }
    }
}
