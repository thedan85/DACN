package com.project.dacn.messaging.outbox;

import com.project.dacn.support.ExternalPostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
class OutboxRelayRepositoryPostgresTest {

    private static final Duration LEASE = Duration.ofSeconds(60);

    private static ExternalPostgresDatabase database;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (database == null) {
            database = ExternalPostgresDatabase.create();
            database.migrate();
        }
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
    }

    @AfterAll
    static void dropScratchDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxRelayRepository repository;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("delete from event_outbox");
    }

    @Test
    void concurrentClaimsTakeDisjointBatchesAndFenceLaterClaims() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderFixture fixture = insertOrder();
        Map<Long, Instant> dueAt = insertDueEvents(fixture.orderPk(), now, 30);

        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<ClaimedOrderEvent>> first = executor.submit(
                    () -> claimAtBarrier(ready, start, 20, now, "relay-a", firstToken));
            Future<List<ClaimedOrderEvent>> second = executor.submit(
                    () -> claimAtBarrier(ready, start, 20, now, "relay-b", secondToken));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ClaimedOrderEvent> firstBatch = first.get(30, TimeUnit.SECONDS);
            List<ClaimedOrderEvent> secondBatch = second.get(30, TimeUnit.SECONDS);

            List<Long> firstIds = firstBatch.stream().map(ClaimedOrderEvent::id).toList();
            List<Long> secondIds = secondBatch.stream().map(ClaimedOrderEvent::id).toList();

            assertThat(List.of(firstBatch.size(), secondBatch.size()))
                    .containsExactlyInAnyOrder(20, 10);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
            assertThat(firstIds).isSortedAccordingTo(byAvailableAt(dueAt));
            assertThat(secondIds).isSortedAccordingTo(byAvailableAt(dueAt));

            List<Long> union = new ArrayList<>(firstIds);
            union.addAll(secondIds);
            union.sort(byAvailableAt(dueAt));
            assertThat(union).containsExactlyElementsOf(expectedOrder(dueAt));

            Instant leaseExpiresAt = now.plus(LEASE);
            for (ClaimedOrderEvent claimed : firstBatch) {
                assertClaimMatchesDatabase(claimed, fixture, "relay-a", firstToken, leaseExpiresAt);
            }
            for (ClaimedOrderEvent claimed : secondBatch) {
                assertClaimMatchesDatabase(claimed, fixture, "relay-b", secondToken, leaseExpiresAt);
            }

            assertThat(repository.claimDue(20, now, LEASE, "relay-c", UUID.randomUUID()))
                    .isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndStaleTokenCannotPublish() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderFixture fixture = insertOrder();
        long expiredRow = insertDueEvent(fixture.orderPk(), 1L, now.minusSeconds(120));
        long liveRow = insertDueEvent(fixture.orderPk(), 2L, now.minusSeconds(60));

        UUID staleToken = UUID.randomUUID();
        assertThat(repository.claimDue(20, now, LEASE, "relay-a", staleToken))
                .extracting(ClaimedOrderEvent::id)
                .containsExactly(expiredRow, liveRow);

        jdbc.update("update event_outbox set lease_expires_at = ? where id = ?",
                Timestamp.from(now.minusSeconds(1)), expiredRow);

        UUID freshToken = UUID.randomUUID();
        List<ClaimedOrderEvent> reclaimed =
                repository.claimDue(20, now, LEASE, "relay-b", freshToken);

        assertThat(reclaimed).singleElement().satisfies(claimed -> {
            assertThat(claimed.id()).isEqualTo(expiredRow);
            assertThat(claimed.claimToken()).isEqualTo(freshToken);
            assertThat(claimed.attemptCount()).isEqualTo(2);
        });

        Instant publishedAt = now.plusSeconds(5);
        assertThat(repository.markPublished(expiredRow, staleToken, publishedAt)).isFalse();
        assertThat(instant(row(expiredRow).get("published_at"))).isNull();

        assertThat(repository.markPublished(expiredRow, freshToken, publishedAt)).isTrue();
        assertThat(instant(row(expiredRow).get("published_at"))).isEqualTo(publishedAt);

        Map<String, Object> published = row(expiredRow);
        assertThat(published.get("lease_owner")).isNull();
        assertThat(published.get("lease_token")).isNull();
        assertThat(instant(published.get("lease_expires_at"))).isNull();
    }

    @Test
    void publishedAndQuarantinedRowsAreNeverClaimed() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderFixture fixture = insertOrder();
        long publishedRow = insertDueEvent(fixture.orderPk(), 1L, now.minusSeconds(180));
        long quarantinedRow = insertDueEvent(fixture.orderPk(), 2L, now.minusSeconds(120));
        long remainingRow = insertDueEvent(fixture.orderPk(), 3L, now.minusSeconds(60));

        UUID token = UUID.randomUUID();
        assertThat(repository.claimDue(20, now, LEASE, "relay-a", token))
                .extracting(ClaimedOrderEvent::id)
                .containsExactly(publishedRow, quarantinedRow, remainingRow);

        assertThat(repository.markPublished(publishedRow, token, now)).isTrue();

        assertThat(repository.quarantine(quarantinedRow, UUID.randomUUID(), now, "EVENT_INVALID"))
                .isFalse();
        assertThat(repository.quarantine(quarantinedRow, token, now, "EVENT_INVALID")).isTrue();

        Map<String, Object> quarantined = row(quarantinedRow);
        assertThat(instant(quarantined.get("quarantined_at"))).isEqualTo(now);
        assertThat(quarantined.get("last_error_code")).isEqualTo("EVENT_INVALID");
        assertThat(quarantined.get("lease_owner")).isNull();
        assertThat(quarantined.get("lease_token")).isNull();
        assertThat(instant(quarantined.get("lease_expires_at"))).isNull();
        assertThat(instant(quarantined.get("published_at"))).isNull();

        List<ClaimedOrderEvent> afterExpiry = repository.claimDue(
                20, now.plus(LEASE).plusSeconds(1), LEASE, "relay-b", UUID.randomUUID());

        assertThat(afterExpiry).extracting(ClaimedOrderEvent::id)
                .containsExactly(remainingRow);
        assertThat(afterExpiry.getFirst().attemptCount()).isEqualTo(2);
    }

    @Test
    void releaseForRetryClearsTheLeaseAndPreservesTheRow() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderFixture fixture = insertOrder();
        long rowId = insertDueEvent(fixture.orderPk(), 1L, now.minusSeconds(60));

        UUID token = UUID.randomUUID();
        ClaimedOrderEvent claimed = repository.claimDue(20, now, LEASE, "relay-a", token)
                .getFirst();
        assertThat(claimed.id()).isEqualTo(rowId);

        assertThat(repository.releaseForRetry(rowId, UUID.randomUUID(), now, "publish_failed"))
                .isFalse();

        Instant availableAt = now.plusSeconds(30);
        assertThat(repository.releaseForRetry(rowId, token, availableAt, "publish_failed"))
                .isTrue();

        Map<String, Object> released = row(rowId);
        assertThat(instant(released.get("available_at"))).isEqualTo(availableAt);
        assertThat(released.get("lease_owner")).isNull();
        assertThat(released.get("lease_token")).isNull();
        assertThat(instant(released.get("lease_expires_at"))).isNull();
        assertThat(((Number) released.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(released.get("last_error_code")).isEqualTo("publish_failed");
        assertThat(instant(released.get("published_at"))).isNull();
        assertThat(instant(released.get("quarantined_at"))).isNull();
        assertThat(released.get("event_id")).isEqualTo(claimed.eventId());
        assertThat(((Number) released.get("order_id")).longValue())
                .isEqualTo(fixture.orderPk());

        List<ClaimedOrderEvent> reclaimed = repository.claimDue(
                20, availableAt, LEASE, "relay-b", UUID.randomUUID());
        assertThat(reclaimed).singleElement().satisfies(retried -> {
            assertThat(retried.id()).isEqualTo(rowId);
            assertThat(retried.attemptCount()).isEqualTo(2);
        });
    }

    @Test
    void claimLimitIsClippedToTheSupportedRange() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderFixture fixture = insertOrder();
        insertDueEvents(fixture.orderPk(), now, 25);

        List<ClaimedOrderEvent> zeroLimit =
                repository.claimDue(0, now, LEASE, "relay-a", UUID.randomUUID());
        assertThat(zeroLimit).hasSize(1);

        List<ClaimedOrderEvent> largeLimit =
                repository.claimDue(999, now, LEASE, "relay-b", UUID.randomUUID());
        assertThat(largeLimit).hasSize(20);
        assertThat(largeLimit).extracting(ClaimedOrderEvent::id)
                .doesNotContain(zeroLimit.getFirst().id());

        List<ClaimedOrderEvent> negativeLimit =
                repository.claimDue(-5, now, LEASE, "relay-c", UUID.randomUUID());
        assertThat(negativeLimit).hasSize(1);
    }

    @Test
    void claimRejectsNonPositiveAndOversizedLeases() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderFixture fixture = insertOrder();
        insertDueEvent(fixture.orderPk(), 1L, now.minusSeconds(60));

        assertThatThrownBy(() -> repository.claimDue(
                20, now, Duration.ZERO, "relay-a", UUID.randomUUID()))
                .hasMessageContaining("lease must be positive")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.claimDue(
                20, now, Duration.ofSeconds(-1), "relay-a", UUID.randomUUID()))
                .hasMessageContaining("lease must be positive")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.claimDue(
                20, now, null, "relay-a", UUID.randomUUID()))
                .hasMessageContaining("lease must be positive")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.claimDue(
                20, now, Duration.ofMinutes(5).plusSeconds(1), "relay-a", UUID.randomUUID()))
                .hasMessageContaining("lease must not exceed 5 minutes")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.claimDue(
                20, now, Duration.ofDays(365), "relay-a", UUID.randomUUID()))
                .hasMessageContaining("lease must not exceed 5 minutes")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        assertThat(repository.claimDue(20, now, Duration.ofMinutes(5), "relay-a", UUID.randomUUID()))
                .singleElement()
                .satisfies(claimed -> assertThat(claimed.attemptCount()).isEqualTo(1));
    }

    @Test
    void claimSkipsRowsLockedByAnotherTransactionWithoutBlocking() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderFixture fixture = insertOrder();
        long lockedRow = insertDueEvent(fixture.orderPk(), 1L, now.minusSeconds(180));
        long firstFreeRow = insertDueEvent(fixture.orderPk(), 2L, now.minusSeconds(120));
        long secondFreeRow = insertDueEvent(fixture.orderPk(), 3L, now.minusSeconds(60));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lockConnection = jdbc.getDataSource().getConnection()) {
            lockConnection.setAutoCommit(false);
            try {
                assertThat(lockRow(lockConnection, lockedRow)).isTrue();

                Future<List<ClaimedOrderEvent>> claim = executor.submit(
                        () -> repository.claimDue(20, now, LEASE, "relay-a", UUID.randomUUID()));

                // Without skip locked this call blocks on the held row and times out here.
                List<ClaimedOrderEvent> claimed = claim.get(5, TimeUnit.SECONDS);
                assertThat(claimed).extracting(ClaimedOrderEvent::id)
                        .containsExactly(firstFreeRow, secondFreeRow)
                        .doesNotContain(lockedRow);
            } finally {
                lockConnection.rollback();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void claimOrdersRowsSharingAvailableAtById() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderFixture fixture = insertOrder();
        Instant sharedAvailableAt = now.minusSeconds(30);
        long first = insertDueEvent(fixture.orderPk(), 1L, sharedAvailableAt);
        long second = insertDueEvent(fixture.orderPk(), 2L, sharedAvailableAt);

        assertThat(repository.claimDue(20, now, LEASE, "relay-a", UUID.randomUUID()))
                .extracting(ClaimedOrderEvent::id)
                .containsExactly(first, second);
    }

    private static boolean lockRow(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from event_outbox where id = ? for update")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private List<ClaimedOrderEvent> claimAtBarrier(CountDownLatch ready, CountDownLatch start,
                                                   int limit, Instant now, String owner,
                                                   UUID token) throws InterruptedException {
        ready.countDown();
        start.await();
        return repository.claimDue(limit, now, LEASE, owner, token);
    }

    private void assertClaimMatchesDatabase(ClaimedOrderEvent claimed, OrderFixture fixture,
                                            String owner, UUID token, Instant leaseExpiresAt) {
        Map<String, Object> persisted = row(claimed.id());

        assertThat(claimed.orderPk()).isEqualTo(fixture.orderPk());
        assertThat(claimed.eventType()).isEqualTo("ORDER_PLACED");
        assertThat(claimed.payload()).isNotBlank();
        assertThat(claimed.claimToken()).isEqualTo(token);
        assertThat(claimed.attemptCount()).isEqualTo(1);

        assertThat(persisted.get("lease_owner")).isEqualTo(owner);
        assertThat(persisted.get("lease_token")).isEqualTo(token);
        assertThat(instant(persisted.get("lease_expires_at"))).isEqualTo(leaseExpiresAt);
        assertThat(((Number) persisted.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(instant(persisted.get("published_at"))).isNull();
        assertThat(instant(persisted.get("quarantined_at"))).isNull();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
                select event_id, order_id, order_sequence, event_type, payload::text as payload,
                       available_at, lease_owner, lease_token, lease_expires_at, attempt_count,
                       published_at, quarantined_at, last_error_code
                  from event_outbox
                 where id = ?
                """, id);
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    private static Comparator<Long> byAvailableAt(Map<Long, Instant> dueAt) {
        return Comparator.<Long, Instant>comparing(dueAt::get).thenComparingLong(Long::longValue);
    }

    private static List<Long> expectedOrder(Map<Long, Instant> dueAt) {
        return dueAt.entrySet().stream()
                .sorted(Map.Entry.<Long, Instant>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<Long, Instant> insertDueEvents(long orderPk, Instant now, int count) {
        Map<Long, Instant> dueAt = new LinkedHashMap<>();
        Instant base = now.minus(Duration.ofHours(1));
        for (int index = 0; index < count; index++) {
            Instant availableAt = base.plusSeconds(index / 2);
            dueAt.put(insertDueEvent(orderPk, index + 1L, availableAt), availableAt);
        }
        return dueAt;
    }

    private long insertDueEvent(long orderPk, long orderSequence, Instant availableAt) {
        UUID eventId = UUID.randomUUID();
        return jdbc.queryForObject("""
                insert into event_outbox
                    (event_id, order_id, order_sequence, event_type, schema_version,
                     payload, available_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                returning id
                """, Long.class, eventId, orderPk, orderSequence, "ORDER_PLACED", 1,
                "{\"eventId\":\"" + eventId + "\"}", Timestamp.from(availableAt));
    }

    private OrderFixture insertOrder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long roleId = jdbc.queryForObject("""
                insert into roles (role_id, role_code, role_name)
                values (?, ?, ?)
                returning id
                """, Long.class, "test-role-" + suffix, "test_role_" + suffix, "Test Role");
        long userId = jdbc.queryForObject("""
                insert into users (user_id, user_email, user_password_hash, role_id)
                values (?, ?, ?, ?)
                returning id
                """, Long.class, "test-user-" + suffix, suffix + "@example.com", "hash", roleId);
        long customerId = jdbc.queryForObject("""
                insert into customers (customer_id, user_id, customer_name, customer_phone)
                values (?, ?, ?, ?)
                returning id
                """, Long.class, "test-customer-" + suffix, userId, "Test Customer",
                "0900000000");
        long orderPk = jdbc.queryForObject("""
                insert into orders
                    (order_id, customer_id, order_total, order_address, order_recipient_name,
                     order_phone)
                values (?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, "TEST_ORDER_" + suffix, customerId, new BigDecimal("199.90"),
                "Test Address", "Test Recipient", "0900000000");
        return new OrderFixture(orderPk);
    }

    private record OrderFixture(long orderPk) {
    }
}
