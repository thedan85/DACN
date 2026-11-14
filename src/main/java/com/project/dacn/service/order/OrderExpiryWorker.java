package com.project.dacn.service.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded owner of scheduled expiry claims; the lifecycle owns each transaction. */
@Slf4j
@Component
public class OrderExpiryWorker {

    public static final String CLAIMS_METRIC = "dacn.inventory.expiry.claims";
    public static final String FAILURE_METRIC = "dacn.inventory.expiry.failures";
    public static final String TICK_METRIC = "dacn.inventory.expiry.tick";

    static final int MAX_CLAIMS_PER_TICK = 50;
    static final int MAX_IN_FLIGHT = 4;

    private final OrderLifecycleService lifecycle;
    private volatile ReservationPolicy reservationPolicy;
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
     * does not stop sweeping. Infrastructure failures stop the tick; failed attempts never count as claims.
     * Known candidate faults instead exclude that identity for the remainder of this tick.
     * The single coordinator drains at most four independent proxy calls before returning.
     */
    @Scheduled(fixedDelay = 5_000)
    public void sweep() {
        Timer.Sample sample = Timer.start(meterRegistry);
        Set<String> excluded = ConcurrentHashMap.newKeySet();
        AtomicBoolean stopped = new AtomicBoolean();
        boolean interrupted = false;
        Throwable taskFailure = null;
        try {
            if (!canSweep()) {
                return;
            }
            try (var executor = new ThreadPoolExecutor(MAX_IN_FLIGHT, MAX_IN_FLIGHT,
                    0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_IN_FLIGHT),
                    Thread.ofPlatform().name("order-expiry-", 0).factory())) {
                var completions = new ExecutorCompletionService<Void>(executor,
                        new ArrayBlockingQueue<>(MAX_IN_FLIGHT));
                int launched = 0;
                int inFlight = 0;
                while (inFlight > 0 || (launched < MAX_CLAIMS_PER_TICK && !stopped.get())) {
                    while (inFlight < MAX_IN_FLIGHT && launched < MAX_CLAIMS_PER_TICK && !stopped.get()) {
                        if (!canSweep()) {
                            stopped.set(true);
                            break;
                        }
                        completions.submit(() -> {
                            claim(stopped, excluded);
                            return null;
                        });
                        launched++;
                        inFlight++;
                    }
                    if (inFlight == 0) {
                        break;
                    }
                    try {
                        // A completion is already done; only take() can wait or be interrupted.
                        completions.take().get();
                        inFlight--;
                    } catch (InterruptedException error) {
                        stopped.set(true);
                        interrupted = true;
                    } catch (ExecutionException error) {
                        inFlight--;
                        stopped.set(true);
                        if (taskFailure == null) taskFailure = error.getCause();
                    }
                }
            }
        } finally {
            sample.stop(tick);
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (taskFailure instanceof Error error) {
            throw error;
        }
        if (taskFailure != null) {
            throw new IllegalStateException("Expiry task failed", taskFailure);
        }
    }

    private void claim(AtomicBoolean stopped, Set<String> excluded) {
        try {
            Set<String> snapshot = Set.copyOf(excluded);
            if (stopped.get() || !canSweep()) {
                stopped.set(true);
                return;
            }
            boolean claimed = snapshot.isEmpty()
                    ? lifecycle.expireNextDue() : lifecycle.expireNextDue(snapshot);
            if (claimed) {
                claims.increment();
            } else {
                stopped.set(true);
            }
        } catch (ExpiryCandidateFailure error) {
            excluded.add(error.orderId());
            failures.increment();
            log.warn("Expiry candidate rolled back with {}", error.getCause().getClass().getSimpleName());
        } catch (RuntimeException error) {
            stopped.set(true);
            failures.increment();
            log.warn("Expiry claim failed with {}", error.getClass().getSimpleName());
        } catch (Error error) {
            stopped.set(true);
            failures.increment();
            throw error;
        }
    }

    private boolean canSweep() {
        ReservationPolicy policy = reservationPolicy;
        return policy.isActive() && !policy.isSweepingPaused();
    }
}
