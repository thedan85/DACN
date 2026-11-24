package com.project.dacn.messaging.outbox;

import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.OrderEventPublisher;
import com.project.dacn.messaging.rabbit.RabbitDeliveryStartup;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notifications.delivery-enabled", havingValue = "true")
public class OrderEventRelay {

    static final int MAX_BATCH = 20;
    static final Duration LEASE = Duration.ofSeconds(60);

    private final OutboxRelayRepository repository;
    private final OrderEventCodec codec;
    private final DatabaseClock clock;
    private final OrderEventAgeGuard ageGuard;
    private final OrderEventPublisher publisher;
    private final OutboxTelemetry telemetry;
    private final RabbitDeliveryStartup deliveryStartup;

    @Scheduled(fixedDelayString = "${app.notifications.relay-interval-ms:500}")
    public void poll() {
        if (!deliveryStartup.isReady()) {
            return;
        }
        Instant claimNow = clock.now();
        UUID claimToken = UUID.randomUUID();
        String owner = UUID.randomUUID().toString();

        List<ClaimedOrderEvent> claimed =
                repository.claimDue(MAX_BATCH, claimNow, LEASE, owner, claimToken);

        for (ClaimedOrderEvent row : claimed) {
            try {
                process(row);
            } catch (RuntimeException e) {
                // One bad row must not stop the rest of the batch.
                log.warn("Outbox relay row {} failed with {}", row.id(), e.getClass().getSimpleName());
            }
        }
    }

    private void process(ClaimedOrderEvent row) {
        OrderEvent event;
        try {
            event = codec.decode(row.payload().getBytes(StandardCharsets.UTF_8));
        } catch (OrderEventCodecException e) {
            quarantine(row, "EVENT_INVALID");
            return;
        }

        // Each row gets its own database time; a later row may cross the age cutoff.
        switch (ageGuard.evaluate(event)) {
            case TOO_OLD -> {
                quarantine(row, "EVENT_TOO_OLD");
                return;
            }
            case FUTURE -> {
                quarantine(row, "EVENT_FUTURE");
                return;
            }
            case OK -> {
            }
        }

        long startNanos = System.nanoTime();
        try {
            publisher.publish(event);
        } catch (RuntimeException e) {
            repository.releaseForRetry(row.id(), row.claimToken(),
                    clock.now().plus(retryDelay(row.attemptCount())), "PUBLISH_FAILED");
            telemetry.recordPublishFailure(Duration.ofNanos(System.nanoTime() - startNanos));
            return;
        }

        repository.markPublished(row.id(), row.claimToken(), clock.now());
        telemetry.recordPublishSuccess(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private void quarantine(ClaimedOrderEvent row, String errorCode) {
        repository.quarantine(row.id(), row.claimToken(), clock.now(), errorCode);
        telemetry.recordQuarantine();
    }

    static Duration retryDelay(int attemptCount) {
        long seconds = 5L << Math.min(Math.max(attemptCount - 1, 0), 4);
        return Duration.ofSeconds(Math.min(seconds, 60));
    }
}
