package com.project.dacn.integration.realtime;

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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class RealtimeRetentionPrunerPostgresTest {

    private static ExternalPostgresDatabase database;
    private static String scratchName;
    private static HikariDataSource pool;
    private JdbcTemplate jdbc;
    private RealtimeRetentionPruner pruner;

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
        config.setMinimumIdle(4);
        config.setConnectionTimeout(10_000);
        config.setPoolName("task16-retention-scratch");
        pool = new HikariDataSource(config);
        JdbcTemplate check = new JdbcTemplate(pool);
        assertThat(check.queryForObject("select current_database()", String.class)).isEqualTo(scratchName);
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
                 PreparedStatement statement = admin.prepareStatement("select 1 from pg_database where datname = ?")) {
                statement.setString(1, scratchName);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("only this invocation's scratch database was dropped").isFalse();
                }
            }
            System.out.println("Task 16 owned scratch cleanup: " + scratchName + "; remaining=0");
        }
    }

    @BeforeEach
    void resetOwnedRetentionRows() {
        jdbc = new JdbcTemplate(pool);
        jdbc.setQueryTimeout(10);
        jdbc.update("delete from realtime_token_leases");
        jdbc.update("delete from realtime_signal_outbox");
        jdbc.update("delete from realtime_send_accounting");
        jdbc.update("update app_realtime_control set enabled = false, remaining_message_budget = 73 where id = 1");
        pruner = new RealtimeRetentionPruner(jdbc);
        ReflectionTestUtils.setField(pruner, "batchSize", 2);
    }

    @Test
    void actualV33SqlPrunesOnlyBoundedExpiredRowsWhilePausedAndPreservesDurableWork() {
        seedEligibleRows(3);
        UUID activeLease = UUID.randomUUID();
        UUID expiryGraceLease = UUID.randomUUID();
        jdbc.update("""
                insert into realtime_token_leases (jti, expires_at, retire_at) values
                    (?, clock_timestamp() + interval '5 minutes', clock_timestamp() + interval '6 minutes'),
                    (?, clock_timestamp() - interval '1 second', clock_timestamp() + interval '29 seconds')
                """, activeLease, expiryGraceLease);
        jdbc.update("""
                insert into realtime_signal_outbox (topic, event_name, payload, sent_at) values
                    ('stock:fresh-pending', 'stock.changed', '{}', null),
                    ('stock:fresh-sent', 'stock.changed', '{}', clock_timestamp())
                """);
        jdbc.update("""
                insert into realtime_send_accounting (topic, reserved_message_cost, sent_at) values
                    ('stock:live-window', 2, clock_timestamp()),
                    ('stock:within-retention', 2, clock_timestamp() - interval '30 minutes')
                """);
        jdbc.execute("""
                with role_row as (
                    insert into roles (role_id, role_code, role_name)
                    values ('task16', 'task16', 'Scratch role') returning id
                ), user_row as (
                    insert into users (user_id, user_email, user_password_hash, role_id)
                    select 'task16', 'task16@example.invalid', 'scratch-only', id from role_row returning id
                ), customer_row as (
                    insert into customers (customer_id, user_id, customer_name, customer_phone)
                    select 'task16', id, 'Scratch customer', '0000000000' from user_row returning id
                ), order_row as (
                    insert into orders (order_id, customer_id, order_total, order_address, order_recipient_name, order_phone)
                    select 'task16', id, 1, 'Scratch address', 'Scratch recipient', '0000000000'
                    from customer_row returning id
                )
                insert into event_outbox (event_id, order_id, order_sequence, event_type, schema_version,
                                          payload, created_at, available_at)
                select '00000000-0000-0000-0000-000000000016'::uuid, id, 1, 'ORDER_PLACED', 1, '{}',
                       clock_timestamp() - interval '45 days', clock_timestamp() from order_row
                """);
        var durableRowsBefore = jdbc.queryForList("select * from event_outbox order by id");
        var controlBefore = jdbc.queryForList("select * from app_realtime_control order by id");

        pruner.prune();

        assertThat(jdbc.queryForObject("select count(*) from realtime_token_leases", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("select jti from realtime_token_leases", UUID.class))
                .contains(activeLease, expiryGraceLease).hasSize(3);
        assertThat(jdbc.queryForList("select topic from realtime_signal_outbox order by id", String.class))
                .containsExactly("stock:old-3", "stock:fresh-pending", "stock:fresh-sent");
        assertThat(jdbc.queryForObject("select sent_at from realtime_signal_outbox where topic = 'stock:fresh-pending'",
                java.sql.Timestamp.class)).isNull();
        assertThat(jdbc.queryForList("select topic from realtime_send_accounting order by id", String.class))
                .containsExactly("stock:old-3", "stock:live-window", "stock:within-retention");
        assertThat(jdbc.queryForObject("""
                select count(*) from event_outbox where published_at is null and quarantined_at is null
                """, Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select remaining_message_budget from app_realtime_control", Long.class))
                .isEqualTo(73);
        assertThat(jdbc.queryForObject("select enabled from app_realtime_control", Boolean.class)).isFalse();
        assertThat(jdbc.getQueryTimeout()).as("the per-statement deadline must not change the shared template").isEqualTo(10);

        // Tick two drains the final eligible rows; tick three has only protected rows left.
        for (int tick = 2; tick <= 3; tick++) {
            pruner.prune();

            assertThat(jdbc.queryForList("select jti from realtime_token_leases", UUID.class))
                    .as("protected leases after tick %s", tick)
                    .containsExactlyInAnyOrder(activeLease, expiryGraceLease);
            assertThat(jdbc.queryForList("select topic from realtime_signal_outbox order by id", String.class))
                    .as("fresh hints after tick %s", tick)
                    .containsExactly("stock:fresh-pending", "stock:fresh-sent");
            assertThat(jdbc.queryForObject("select sent_at from realtime_signal_outbox where topic = 'stock:fresh-pending'",
                    java.sql.Timestamp.class)).isNull();
            assertThat(jdbc.queryForList("select topic from realtime_send_accounting order by id", String.class))
                    .as("protected accounting after tick %s", tick)
                    .containsExactly("stock:live-window", "stock:within-retention");
            assertThat(jdbc.queryForObject("""
                    select count(*) from realtime_token_leases where retire_at <= clock_timestamp()
                    """, Long.class)).as("eligible leases after tick %s", tick).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from realtime_signal_outbox
                     where created_at <= clock_timestamp() - interval '30 seconds'
                    """, Long.class)).as("eligible hints after tick %s", tick).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from realtime_send_accounting
                     where sent_at <= clock_timestamp() - interval '1 hour'
                    """, Long.class)).as("eligible accounting after tick %s", tick).isZero();
            assertThat(jdbc.queryForList("select * from event_outbox order by id"))
                    .as("pending durable rows unchanged after tick %s", tick).isEqualTo(durableRowsBefore);
            assertThat(jdbc.queryForList("select * from app_realtime_control order by id"))
                    .as("paused control unchanged after tick %s", tick).isEqualTo(controlBefore);
        }
    }

    @Test
    void skipsRowsHeldByDispatchOrAnotherPrunerAndNeverWaitsOnTheControlLock() throws Exception {
        seedEligibleRows(2);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = pool.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (Statement statement = blocker.createStatement()) {
                try (ResultSet rows = statement.executeQuery("select pg_backend_pid()")) {
                    assertThat(rows.next()).isTrue();
                    blockerPid = rows.getInt(1);
                }
                statement.execute("select id from app_realtime_control where id = 1 for update");
                statement.execute("select jti from realtime_token_leases order by retire_at limit 1 for update");
                statement.execute("select id from realtime_signal_outbox order by id limit 1 for update");
                statement.execute("select id from realtime_send_accounting order by id limit 1 for update");
            }
            try {
                Future<?> pruning = executor.submit(pruner::prune);
                observeLockWait(pruning, blockerPid, "row");
                assertThatCode(() -> pruning.get(5, TimeUnit.SECONDS))
                        .as("retention must skip locked rows even while dispatch holds the control lock")
                        .doesNotThrowAnyException();
                assertThat(jdbc.queryForObject("select count(*) from realtime_token_leases", Long.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from realtime_signal_outbox", Long.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from realtime_send_accounting", Long.class)).isEqualTo(1);
            } finally {
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableLockWaitIsBoundedAndDoesNotPreventTheOtherRetentionBatches() throws Exception {
        seedEligibleRows(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = pool.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (Statement statement = blocker.createStatement()) {
                try (ResultSet rows = statement.executeQuery("select pg_backend_pid()")) {
                    assertThat(rows.next()).isTrue();
                    blockerPid = rows.getInt(1);
                }
                statement.execute("lock table realtime_token_leases in access exclusive mode");
            }
            try {
                Future<?> pruning = executor.submit(pruner::prune);
                observeLockWait(pruning, blockerPid, "table");
                assertThatCode(() -> pruning.get(5, TimeUnit.SECONDS))
                        .as("one blocked table must not monopolize the scheduler or stop independent cleanup")
                        .doesNotThrowAnyException();
                jdbc.queryForObject("select id from app_realtime_control where id = 1 for update nowait", Integer.class);
                assertThat(jdbc.queryForObject("select count(*) from realtime_signal_outbox", Long.class)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from realtime_send_accounting", Long.class)).isZero();
            } finally {
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from realtime_token_leases", Long.class)).isEqualTo(1);
    }

    private void observeLockWait(Future<?> pruning, int blockerPid, String kind) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean observed = false;
        while (!pruning.isDone() && !observed && System.nanoTime() < deadline) {
            observed = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (select 1 from pg_stat_activity
                                    where datname = current_database() and wait_event_type = 'Lock'
                                      and ? = any(pg_blocking_pids(pid)))
                    """, Boolean.class, blockerPid));
            if (!observed && !pruning.isDone()) {
                Thread.sleep(25);
            }
        }
        System.out.println("Task 16 " + kind + " lock wait observed=" + observed);
    }

    private void seedEligibleRows(int count) {
        for (int index = 1; index <= count; index++) {
            jdbc.update("""
                    insert into realtime_token_leases (jti, expires_at, retire_at)
                    values (?, clock_timestamp() - interval '2 minutes', clock_timestamp() - interval '1 minute')
                    """, UUID.randomUUID());
            jdbc.update("""
                    insert into realtime_signal_outbox (topic, event_name, payload, created_at, sent_at)
                    values (?, 'stock.changed', '{}', clock_timestamp() - interval '1 minute',
                            case when ? = 2 then clock_timestamp() - interval '1 minute' else null end)
                    """, "stock:old-" + index, index);
            jdbc.update("""
                    insert into realtime_send_accounting (topic, reserved_message_cost, sent_at)
                    values (?, 2, clock_timestamp() - interval '2 hours')
                    """, "stock:old-" + index);
        }
    }
}
