package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class NotificationRetentionPrunerPostgresTest {

    private static ExternalPostgresDatabase database;
    private static String scratchName;
    private static HikariDataSource pool;
    private static long userId;
    private static long orderId;

    private JdbcTemplate jdbc;
    private NotificationRetentionPruner pruner;

    @BeforeAll
    static void createOwnedDatabase() {
        database = ExternalPostgresDatabase.create();
        scratchName = URI.create(database.jdbcUrl().substring("jdbc:".length())).getPath().substring(1);
        assertThat(scratchName).startsWith("verify_event_foundation_");
        database.migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000);
        config.setPoolName("task15-retention-scratch");
        pool = new HikariDataSource(config);
        JdbcTemplate check = new JdbcTemplate(pool);
        assertThat(check.queryForObject("select current_database()", String.class)).isEqualTo(scratchName);
        assertThat(check.queryForObject("select version from flyway_schema_history where version = '38' and success",
                String.class)).isEqualTo("38");
        seedBaseEntities(check);
        userId = check.queryForObject("select id from users where user_id = 'task15'", Long.class);
        orderId = check.queryForObject("select id from orders where order_id = 'task15'", Long.class);
        System.out.println("Task 15 scratch database ready: " + scratchName + " (migrated through V38)");
    }

    private static void seedBaseEntities(JdbcTemplate check) {
        check.update("""
                with role_row as (
                    insert into roles (role_id, role_code, role_name)
                    values ('task15', 'task15', 'Scratch role') returning id
                ), user_row as (
                    insert into users (user_id, user_email, user_password_hash, role_id)
                    select 'task15', 'task15@example.invalid', 'scratch-only', id from role_row returning id
                ), customer_row as (
                    insert into customers (customer_id, user_id, customer_name, customer_phone)
                    select 'task15', id, 'Scratch customer', '0000000000' from user_row returning id
                )
                insert into orders (order_id, customer_id, order_total, order_address,
                                    order_recipient_name, order_phone)
                select 'task15', id, 1, 'Scratch address', 'Scratch recipient', '0000000000'
                from customer_row
                """);
    }

    @AfterAll
    static void dropOnlyOwnedDatabase() throws Exception {
        if (pool != null) {
            pool.close();
        }
        if (database != null) {
            database.drop();
            try (Connection admin = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                    database.username(), database.password());
                 PreparedStatement statement = admin.prepareStatement(
                         "select 1 from pg_database where datname = ?")) {
                statement.setString(1, scratchName);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("only this invocation's scratch database was dropped").isFalse();
                }
            }
            System.out.println("Task 15 owned scratch cleanup: " + scratchName + "; remaining=0");
        }
    }

    @BeforeEach
    void resetOwnedRetentionRows() {
        jdbc = new JdbcTemplate(pool);
        jdbc.setQueryTimeout(10);
        jdbc.update("delete from notification_event_receipts");
        jdbc.update("delete from email_deliveries");
        jdbc.update("delete from event_outbox");
        jdbc.update("delete from notifications");
        jdbc.update("update users set inbox_revision = 0 where id = ?", userId);
        jdbc.update("update app_realtime_control set enabled = false, remaining_message_budget = 73 where id = 1");
        pruner = new NotificationRetentionPruner(jdbc, mock(NotificationRetentionTelemetry.class));
        ReflectionTestUtils.setField(pruner, "batchSize", 2);
    }

    @Test
    void boundedTicksPruneOnlyEligibleRowsAndPreserveProtectedState() {
        seedNotifications(5, "'90 days'", "READ", false, "old");
        seedNotifications(1, "'89 days 23 hours'", "READ", false, "edge");
        seedNotifications(1, "'1 hour'", "READ", false, "freshread");
        seedNotifications(1, "'1 hour'", "READ", true, "freshdeleted");
        for (int i = 0; i < 5; i++) {
            seedOutbox(UUID.randomUUID(), i + 1, "'30 days'", false, true);
        }
        seedOutbox(UUID.randomUUID(), 10, "'29 days 23 hours'", false, true);
        seedOutbox(UUID.randomUUID(), 11, "'40 days'", false, false);
        seedOutbox(UUID.randomUUID(), 12, "'45 days'", true, false);
        for (int i = 0; i < 5; i++) {
            seedEmail(UUID.randomUUID(), i % 2 == 0 ? "SENT" : "SKIPPED", "'90 days'", true);
        }
        seedEmail(UUID.randomUUID(), "SENT", "'89 days 23 hours'", true);
        seedEmail(UUID.randomUUID(), "PENDING", "'120 days'", false);
        seedEmail(UUID.randomUUID(), "IN_FLIGHT", "'120 days'", false);
        seedEmail(UUID.randomUUID(), "UNKNOWN", "'120 days'", false);
        seedEmail(UUID.randomUUID(), "FAILED", "'120 days'", false);
        for (int i = 0; i < 5; i++) {
            seedReceipt(UUID.randomUUID(), "'90 days'", true);
        }
        seedReceipt(UUID.randomUUID(), "'89 days 23 hours'", true);
        seedReceipt(UUID.randomUUID(), "'1 hour'", false);
        jdbc.update("update users set inbox_revision = 42 where id = ?", userId);

        long[][] expectedEligible = {{3, 3, 3, 3}, {1, 1, 1, 1}, {0, 0, 0, 0}, {0, 0, 0, 0}};
        for (int tick = 0; tick < expectedEligible.length; tick++) {
            pruner.prune();
            assertThat(new long[]{eligibleNotifications(), eligibleOutbox(), eligibleEmail(), eligibleReceipts()})
                    .as("eligible backlog after tick %s", tick + 1)
                    .containsExactly(expectedEligible[tick]);
        }

        assertThat(count("select count(*) from notifications")).isEqualTo(3);
        assertThat(count("select count(*) from event_outbox")).isEqualTo(3);
        assertThat(count("select count(*) from email_deliveries")).isEqualTo(5);
        assertThat(count("select count(*) from notification_event_receipts")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select notification_status from notifications where notification_id like 'N_freshread_%'
                """, String.class)).isEqualTo("READ");
        assertThat(jdbc.queryForObject("""
                select is_deleted from notifications where notification_id like 'N_freshdeleted_%'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select inbox_revision from users where id = ?", Long.class, userId))
                .isEqualTo(42);
        assertThat(jdbc.queryForObject("select remaining_message_budget from app_realtime_control", Long.class))
                .isEqualTo(73);
        assertThat(jdbc.queryForObject("select enabled from app_realtime_control", Boolean.class)).isFalse();
        assertThat(jdbc.getQueryTimeout()).as("per-statement deadline must not mutate the shared template")
                .isEqualTo(10);
    }

    @Test
    void skipsEligibleRowHeldByAnotherTransactionAndRemovesItOnLaterTick() throws Exception {
        seedNotifications(3, "'90 days'", "UNREAD", false, "lock");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = pool.getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select id from notifications order by id limit 1 for update")) {
                assertThat(rows.next()).isTrue();
            }
            try {
                Future<?> pruning = executor.submit(pruner::prune);
                assertThatCode(() -> pruning.get(6, TimeUnit.SECONDS))
                        .as("skip locked must not wait for the row lock holder")
                        .doesNotThrowAnyException();
                assertThat(count("select count(*) from notifications"))
                        .as("locked eligible row skipped, two others pruned").isEqualTo(1);
            } finally {
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        pruner.prune();
        assertThat(count("select count(*) from notifications"))
                .as("previously locked row removed on a later tick").isZero();
    }

    @Test
    void tableLockOnOneBatchDoesNotStopOtherBatches() throws Exception {
        seedNotifications(1, "'90 days'", "UNREAD", false, "blocked");
        seedOutbox(UUID.randomUUID(), 1, "'30 days'", false, true);
        seedEmail(UUID.randomUUID(), "SENT", "'90 days'", true);
        seedReceipt(UUID.randomUUID(), "'90 days'", true);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = pool.getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute("lock table notifications in access exclusive mode");
            }
            try {
                Future<?> pruning = executor.submit(pruner::prune);
                assertThatCode(() -> pruning.get(8, TimeUnit.SECONDS))
                        .as("one blocked table must not monopolize the scheduler")
                        .doesNotThrowAnyException();
                assertThat(eligibleOutbox()).as("outbox pruned while notifications blocked").isZero();
                assertThat(eligibleEmail()).as("email pruned while notifications blocked").isZero();
                assertThat(eligibleReceipts()).as("receipts pruned while notifications blocked").isZero();
            } finally {
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("select count(*) from notifications"))
                .as("notifications row survived the blocked batch").isEqualTo(1);
        assertThat(jdbc.getQueryTimeout()).isEqualTo(10);
    }

    @Test
    void noCascadeAndAgeGuardStillBlocksAfterEvidencePruned() {
        UUID survivedEvent = UUID.randomUUID();
        seedEventNotification(survivedEvent, "'90 days'");
        seedOutbox(survivedEvent, 1, "'30 days'", false, true);
        seedReceipt(survivedEvent, "'10 days'", true);

        UUID prunedEvent = UUID.randomUUID();
        seedReceipt(prunedEvent, "'90 days'", true);
        seedNotifications(1, "'1 hour'", "UNREAD", false, "guardfresh");
        jdbc.update("update users set inbox_revision = 42 where id = ?", userId);
        long otherNotificationsBefore = count("select count(*) from notifications where notification_id not like 'N_EVT_%'");

        pruner.prune();

        assertThat(count("select count(*) from notification_event_receipts where event_id = ?", survivedEvent))
                .as("young completed receipt survives while its source rows are pruned").isEqualTo(1);
        assertThat(count("select count(*) from notifications where source_event_id = ?", survivedEvent)).isZero();
        assertThat(count("select count(*) from event_outbox where event_id = ?", survivedEvent)).isZero();
        assertThat(count("select count(*) from notification_event_receipts where event_id = ?", prunedEvent))
                .as("old completed receipt pruned").isZero();

        OrderEventAgeGuard guard = new OrderEventAgeGuard(mock(DatabaseClock.class));
        Instant now = Instant.now();
        OrderEvent oldEvent = event(prunedEvent, now.minus(31, ChronoUnit.DAYS));
        OrderEvent youngEvent = event(prunedEvent, now.minus(29, ChronoUnit.DAYS));
        assertThat(guard.evaluate(oldEvent, now)).as("no dedup row: 31-day event is TOO_OLD")
                .isEqualTo(OrderEventAgeGuard.AgeDecision.TOO_OLD);
        assertThat(guard.evaluate(youngEvent, now)).as("no dedup row: 29-day event is OK")
                .isEqualTo(OrderEventAgeGuard.AgeDecision.OK);
        assertThat(count("select count(*) from notifications where notification_id not like 'N_EVT_%'"))
                .as("age guard evaluation creates no notification").isEqualTo(otherNotificationsBefore);
        assertThat(jdbc.queryForObject("select inbox_revision from users where id = ?", Long.class, userId))
                .as("age guard evaluation changes no revision").isEqualTo(42);
    }

    @Test
    void runnerLeavesDurableUnresolvedWorkAndBudgetsUntouched() {
        seedOutbox(UUID.randomUUID(), 1, "'40 days'", false, false);
        seedOutbox(UUID.randomUUID(), 2, "'45 days'", true, false);
        seedEmail(UUID.randomUUID(), "UNKNOWN", "'120 days'", false);
        seedEmail(UUID.randomUUID(), "FAILED", "'120 days'", false);
        seedReceipt(UUID.randomUUID(), "'1 hour'", false);
        seedNotifications(1, "'1 hour'", "UNREAD", false, "durable");

        pruner.prune();
        pruner.prune();

        assertThat(count("select count(*) from event_outbox")).isEqualTo(2);
        assertThat(count("select count(*) from email_deliveries where state in ('UNKNOWN','FAILED')")).isEqualTo(2);
        assertThat(count("select count(*) from notification_event_receipts where completed_at is null")).isEqualTo(1);
        assertThat(count("select count(*) from notifications")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select remaining_message_budget from app_realtime_control", Long.class))
                .isEqualTo(73);
        assertThat(jdbc.queryForObject("select enabled from app_realtime_control", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select inbox_revision from users where id = ?", Long.class, userId))
                .isZero();
    }

    private static OrderEvent event(UUID eventId, Instant occurredAt) {
        return new OrderEvent(1, eventId, OrderEventType.ORDER_PLACED, "ORD_task15", 1L, occurredAt,
                "42", OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD, "10.00", null,
                "corr-task15", "CHECKOUT_WEB");
    }

    private long eligibleNotifications() {
        return count("select count(*) from notifications "
                + "where coalesce(occurred_at, created_at) <= clock_timestamp() - interval '90 days'");
    }

    private long eligibleOutbox() {
        return count("select count(*) from event_outbox "
                + "where published_at <= clock_timestamp() - interval '30 days' and quarantined_at is null");
    }

    private long eligibleEmail() {
        return count("select count(*) from email_deliveries "
                + "where state in ('SENT','SKIPPED') and completed_at <= clock_timestamp() - interval '90 days'");
    }

    private long eligibleReceipts() {
        return count("select count(*) from notification_event_receipts "
                + "where completed_at <= clock_timestamp() - interval '90 days'");
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void seedNotifications(int count, String intervalLiteral, String status, boolean deleted, String tag) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    insert into notifications (notification_id, user_id, notification_message,
                                               notification_status, is_deleted, occurred_at, created_at)
                    values (?, ?, 'seed', ?, ?, clock_timestamp() - interval %s,
                            clock_timestamp() - interval %s)
                    """.formatted(intervalLiteral, intervalLiteral),
                    "N_" + tag + "_" + i + "_" + UUID.randomUUID(), userId, status, deleted);
        }
    }

    private void seedEventNotification(UUID eventId, String intervalLiteral) {
        jdbc.update("""
                insert into notifications (notification_id, user_id, notification_message, notification_status,
                                           source_event_id, order_id, event_type, order_sequence,
                                           occurred_at, recipient_scope)
                values (?, ?, 'seed', 'UNREAD', ?, ?, 'ORDER_PLACED', 1,
                        clock_timestamp() - interval %s, 'CUSTOMER')
                """.formatted(intervalLiteral), "N_EVT_" + UUID.randomUUID(), userId, eventId, orderId);
    }

    private void seedOutbox(UUID eventId, long sequence, String publishedInterval,
                            boolean quarantined, boolean published) {
        String publishedSql = published ? "clock_timestamp() - interval " + publishedInterval : "null";
        String quarantinedSql = quarantined ? "clock_timestamp() - interval '1 day'" : "null";
        jdbc.update("""
                insert into event_outbox (event_id, order_id, order_sequence, event_type, schema_version,
                                          payload, created_at, available_at, published_at, quarantined_at)
                values (?, ?, ?, 'ORDER_PLACED', 1, '{}'::jsonb,
                        clock_timestamp() - interval '40 days', clock_timestamp() - interval '40 days',
                        %s, %s)
                """.formatted(publishedSql, quarantinedSql), eventId, orderId, sequence);
    }

    private void seedEmail(UUID eventId, String state, String completedInterval, boolean completed) {
        String completedSql = completed ? "clock_timestamp() - interval " + completedInterval : "null";
        jdbc.update("""
                insert into email_deliveries (event_id, recipient_user_id, state, attempt_count,
                                              created_at, updated_at, completed_at)
                values (?, ?, ?, 1, clock_timestamp() - interval '95 days',
                        clock_timestamp() - interval '95 days', %s)
                """.formatted(completedSql), eventId, userId, state);
    }

    private void seedReceipt(UUID eventId, String completedInterval, boolean completed) {
        String completedSql = completed ? "clock_timestamp() - interval " + completedInterval : "null";
        jdbc.update("""
                insert into notification_event_receipts (event_id, recipient_user_ids,
                                                        created_at, updated_at, completed_at)
                values (?, '[]'::jsonb, clock_timestamp() - interval '95 days',
                        clock_timestamp() - interval '95 days', %s)
                """.formatted(completedSql), eventId);
    }
}
