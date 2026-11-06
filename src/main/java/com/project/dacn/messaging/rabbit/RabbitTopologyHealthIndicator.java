package com.project.dacn.messaging.rabbit;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Readiness contributor named {@code rabbitTopology}. Delivery disabled is a
 * valid state, so the indicator stays up with {@code delivery=disabled}.
 * Computed health is cached briefly so probes do not run every management GET.
 */
@Component("rabbitTopology")
public class RabbitTopologyHealthIndicator implements HealthIndicator {

    private static final long CACHE_NANOS = Duration.ofSeconds(5).toNanos();

    private final Optional<RabbitTopologyVerifier> verifier;
    private volatile Health cachedHealth;
    private volatile long cachedAtNanos;

    public RabbitTopologyHealthIndicator(Optional<RabbitTopologyVerifier> verifier) {
        this.verifier = verifier;
    }

    @Override
    public Health health() {
        if (verifier.isEmpty()) {
            return Health.up().withDetail("delivery", "disabled").build();
        }
        Health cached = cachedHealth;
        if (cached != null) {
            long age = System.nanoTime() - cachedAtNanos;
            if (age >= 0 && age < CACHE_NANOS) {
                return cached;
            }
        }
        Health health = computeHealth();
        // Publish the value before the timestamp: a racing reader that sees a
        // stale timestamp recomputes instead of serving stale health.
        cachedHealth = health;
        cachedAtNanos = System.nanoTime();
        return health;
    }

    private Health computeHealth() {
        try {
            verifier.get().verify();
            return Health.up().build();
        } catch (TopologyVerificationException e) {
            Health.Builder builder = Health.down().withDetail("code", e.code().name());
            if (e.queueName() != null) {
                builder.withDetail("queue", e.queueName());
            }
            return builder.build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("code", "VERIFICATION_FAILED").build();
        }
    }
}
