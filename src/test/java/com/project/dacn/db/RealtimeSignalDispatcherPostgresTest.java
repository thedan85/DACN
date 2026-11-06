package com.project.dacn.db;

import com.project.dacn.support.ExternalPostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class RealtimeSignalDispatcherPostgresTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private static final String STUBS = """
            create schema if not exists realtime;
            create table realtime.messages (
                id bigint generated always as identity primary key,
                extension text, topic text, event text, payload jsonb,
                private boolean not null default true
            );
            create table public.realtime_test_capture (
                topic text, event text, payload jsonb, private boolean,
                captured_at timestamptz not null default clock_timestamp()
            );
            create table public.realtime_test_fail_send (active boolean, topic text);
            create or replace function realtime.topic() returns text
                language sql stable as $$
                    select coalesce(current_setting('realtime.topic', true), '')
                $$;
            create or replace function realtime.send(
                    payload jsonb, event text, topic text, private boolean)
            returns void language plpgsql as $$
            begin
                if exists (select 1 from public.realtime_test_fail_send f
                            where f.active and (f.topic is null or f.topic = $3)) then
                    if exists (select 1 from public.realtime_test_capture) then
                        raise exception 'send failed after earlier capture';
                    end if;
                    raise exception 'send failed';
                end if;
                insert into public.realtime_test_capture (topic, event, payload, private)
                values (topic, event, payload, private);
            end $$;
            create or replace function public.realtime_test_newer_hint()
            returns trigger language plpgsql as $$
            begin
                if new.topic = 'stock:newer' then
                    insert into public.realtime_signal_outbox (topic, event_name, payload, created_at)
                    values ('stock:newer', 'stock.changed', '{"productId":"newer"}',
                            '2026-11-04T00:00:00Z');
                end if;
                return new;
            end $$;
            create trigger realtime_test_newer_hint after insert on public.realtime_test_capture
                for each row execute function public.realtime_test_newer_hint();
            do $$
            begin
                if not exists (select 1 from pg_roles where rolname = 'anon')
                   or not exists (select 1 from pg_roles where rolname = 'authenticated') then
                    raise exception 'Scratch dispatcher test requires existing anon and authenticated roles; never create cluster roles';
                end if;
            end $$;
            """;

    private static ExternalPostgresDatabase database;
    private static String script;
    private static String scratchName;
    private static long inboxUserId;

    private JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() throws Exception {
        database = ExternalPostgresDatabase.create();
        scratchName = URI.create(database.jdbcUrl().substring("jdbc:".length()))
                .getPath().substring(1);
        assertThat(scratchName).startsWith("verify_event_foundation_");
        database.migrate();
        script = Files.readString(
                Path.of("src/main/resources/db/supabase/notification_realtime.sql"));
        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("select current_database()")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(scratchName);
            }
            statement.execute(STUBS);
            statement.execute(script);
            statement.execute("""
                    insert into roles (role_id, role_code, role_name)
                    values ('role-task15', 'task15', 'Task 15 scratch fixture');
                    insert into users (user_id, user_email, user_password_hash, role_id, user_status)
                    select 'user-task15', 'task15@example.invalid', 'scratch-only', id, 'ACTIVE'
                      from roles where role_id = 'role-task15';
                    insert into users (user_id, user_email, user_password_hash, role_id, user_status)
                    select 'user-task15-other', 'task15-other@example.invalid', 'scratch-only', id, 'ACTIVE'
                      from roles where role_id = 'role-task15';
                    insert into customers (customer_id, user_id, customer_name, customer_phone)
                    select 'customer-task15', id, 'Task 15 scratch customer', '0000000000'
                      from users where user_id = 'user-task15';
                    """);
            try (ResultSet rows = statement.executeQuery(
                    "select id from users where user_id = 'user-task15'")) {
                assertThat(rows.next()).isTrue();
                inboxUserId = rows.getLong(1);
            }
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (database != null) {
            database.drop();
            try (Connection connection = DriverManager.getConnection(
                    System.getenv("TEST_POSTGRES_URL"), database.username(), database.password());
                 PreparedStatement statement = connection.prepareStatement(
                         "select count(*) from pg_database where datname = ?")) {
                statement.setString(1, scratchName);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1))
                            .as("Only this invocation's scratch database is removed").isZero();
                }
            }
            System.out.println("Task 15 scratch cleanup: " + scratchName + "; remaining=0");
        }
    }

    @BeforeEach
    void reset() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                database.jdbcUrl(), database.username(), database.password());
        jdbc = new JdbcTemplate(source);
        jdbc.update("delete from public.realtime_test_capture");
        jdbc.update("delete from public.realtime_signal_outbox");
        jdbc.update("delete from public.realtime_send_accounting");
        jdbc.update("delete from public.realtime_token_leases");
        jdbc.update("delete from public.realtime_test_fail_send");
        control(true, 1000);
    }

    @Test
    void disabledControlSendsNothing() {
        control(false, 1000);
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);
        lease("p1", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(NOW)).isZero();
        assertThat(sends()).isZero();
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(budget()).isEqualTo(1000);
        assertThat(accountedCost()).isZero();
    }

    @Test
    void zeroBudgetSendsNothing() {
        control(true, 0);
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);
        lease("p1", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(NOW)).isZero();
        assertThat(sends()).isZero();
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(budget()).isZero();
        assertThat(accountedCost()).isZero();
    }

    @Test
    void oneStockSendReservesOnePlusTheUnretiredReceivers() {
        Instant now = NOW;
        control(true, 100);
        hint("stock:p1", "{\"productId\":\"p1\",\"privateExtra\":\"discarded\"}", now);
        lease("p1", false, null, Duration.ofMinutes(10));
        lease("p1", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(now)).isEqualTo(1);

        assertThat(sends()).isEqualTo(1);
        assertThat(budget()).isEqualTo(97);
        assertThat(jdbc.queryForObject(
                "select reserved_message_cost from public.realtime_send_accounting",
                Integer.class)).isEqualTo(3);
        assertThat(pendingHints()).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from public.realtime_test_capture
                 where topic = 'stock:p1' and event = 'stock.changed' and private
                   and payload = '{"productId":"p1"}'::jsonb
                """, Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from public.realtime_send_accounting a
                join public.realtime_signal_outbox h on h.topic = a.topic
                join public.app_realtime_control c on c.id = 1
                 where a.sent_at = ? and a.sent_at = h.sent_at and a.sent_at = c.updated_at
                """, Long.class, Timestamp.from(now))).isEqualTo(1);
    }

    @Test
    void inboxHintsCoalesceToTheHighestRevisionAndSendOnce() {
        Instant now = NOW;
        hint("inbox:" + inboxUserId, "{\"revision\":2}", now);
        long highest = hint("inbox:" + inboxUserId, "{\"revision\":5}", now);
        hint("inbox:" + inboxUserId, "{\"revision\":3}", now);
        lease(null, true, inboxUserId, Duration.ofMinutes(10));

        assertThat(dispatch(now)).isEqualTo(1);

        assertThat(sends()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select payload ->> 'revision' from public.realtime_test_capture",
                String.class)).isEqualTo("5");
        assertThat(pendingHints()).isZero();
        assertThat(budget()).isEqualTo(998);
        assertThat(accountedCost()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from public.realtime_test_capture
                 where event = 'inbox.changed' and payload = '{"revision":5}'::jsonb and private
                """, Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                select reserved_message_cost from public.realtime_signal_outbox order by id
                """, Integer.class)).containsExactly(0, 2, 0);
        assertThat(jdbc.queryForObject("""
                select reserved_message_cost from public.realtime_signal_outbox where id = ?
                """, Integer.class, highest)).isEqualTo(2);
    }

    @Test
    void aNewerHintInsertedDuringDispatchStaysPending() {
        Instant now = NOW;
        long originalId = hint("stock:newer", "{\"productId\":\"newer\"}", now);
        lease("newer", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(now)).isEqualTo(1);

        assertThat(sends()).isEqualTo(1);
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select payload ->> 'productId' from public.realtime_signal_outbox where sent_at is null",
                String.class)).isEqualTo("newer");
        assertThat(jdbc.queryForObject("""
                select id from public.realtime_signal_outbox where sent_at is null
                """, Long.class)).isGreaterThan(originalId);
        assertThat(jdbc.queryForObject("""
                select count(*) from public.realtime_signal_outbox where id = ? and sent_at = ?
                """, Long.class, originalId, Timestamp.from(now))).isEqualTo(1);
    }

    @Test
    void zeroRecipientsMarksTheHintProcessedWithoutSending() {
        Instant now = NOW;
        hint("stock:p1", "{\"productId\":\"p1\"}", now);

        assertThat(dispatch(now)).isZero();

        assertThat(sends()).isZero();
        assertThat(pendingHints()).isZero();
        assertThat(jdbc.queryForObject(
                "select reserved_message_cost from public.realtime_signal_outbox",
                Integer.class)).isZero();
        assertThat(budget()).isEqualTo(1000);
        assertThat(accountedCost()).isZero();
    }

    @Test
    void theSixtyMessageWindowDoesNotBurstAtTheBoundary() {
        Instant now = NOW;
        jdbc.update("""
                insert into public.realtime_send_accounting (topic, reserved_message_cost, sent_at)
                values ('stock:other', 59, ?)
                """, Timestamp.from(now));
        hint("stock:p1", "{\"productId\":\"p1\"}", now);
        lease("p1", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(now)).isZero();
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(dispatch(now.plusNanos(999_999_000))).isZero();
        assertThat(sends()).isZero();
        assertThat(budget()).isEqualTo(1000);
        assertThat(accountedCost()).isEqualTo(59);

        assertThat(dispatch(now.plusSeconds(1))).isEqualTo(1);
        assertThat(pendingHints()).isZero();
        assertThat(sends()).isEqualTo(1);
        assertThat(budget()).isEqualTo(998);
        assertThat(accountedCost()).isEqualTo(61);
    }

    @Test
    void twoDispatchesAtTheSameInstantDoNotDoubleSpend() {
        Instant now = NOW;
        control(true, 100);
        jdbc.update("""
                insert into public.realtime_send_accounting (topic, reserved_message_cost, sent_at)
                values ('stock:other', 58, ?)
                """, Timestamp.from(now));
        hint("stock:p1", "{\"productId\":\"p1\"}", now);
        lease("p1", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(now)).isEqualTo(1);
        hint("stock:p2", "{\"productId\":\"p2\"}", now);
        lease("p2", false, null, Duration.ofMinutes(10));
        assertThat(dispatch(now)).isZero();
        assertThat(sends()).isEqualTo(1);
        assertThat(budget()).isEqualTo(98);
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(accountedCost()).isEqualTo(60);
    }

    @Test
    void costAboveRemainingBudgetLeavesTheReserveUntouched() {
        Instant now = NOW;
        control(true, 1);
        hint("stock:p1", "{\"productId\":\"p1\"}", now);
        lease("p1", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(now)).isZero();

        assertThat(budget()).isEqualTo(1);
        assertThat(sends()).isZero();
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(accountedCost()).isZero();
    }

    @Test
    void hintsOlderThanThirtySecondsAreProcessedAsStale() {
        Instant now = NOW;
        hint("stock:p1", "{\"productId\":\"p1\"}", now.minusSeconds(31));
        lease("p1", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(now)).isZero();

        assertThat(sends()).isZero();
        assertThat(pendingHints()).isZero();
        assertThat(jdbc.queryForObject(
                "select reserved_message_cost from public.realtime_signal_outbox",
                Integer.class)).isZero();
        assertThat(budget()).isEqualTo(1000);
        assertThat(accountedCost()).isZero();
    }

    @Test
    void stockAndInboxShareOneWindow() {
        Instant now = NOW;
        jdbc.update("""
                insert into public.realtime_send_accounting (topic, reserved_message_cost, sent_at)
                values ('inbox:other', 59, ?)
                """, Timestamp.from(now));
        hint("stock:p1", "{\"productId\":\"p1\"}", now);
        hint("inbox:" + inboxUserId, "{\"revision\":1}", now);
        lease("p1", false, null, Duration.ofMinutes(10));
        lease(null, true, inboxUserId, Duration.ofMinutes(10));

        assertThat(dispatch(now)).isZero();
        assertThat(pendingHints()).isEqualTo(2);
        assertThat(sends()).isZero();
        assertThat(budget()).isEqualTo(1000);
        assertThat(dispatch(now.plusSeconds(1))).isEqualTo(2);
        assertThat(sends()).isEqualTo(2);
        assertThat(pendingHints()).isZero();
        assertThat(budget()).isEqualTo(996);
        assertThat(jdbc.queryForObject("""
                select sum(reserved_message_cost) from public.realtime_send_accounting where sent_at = ?
                """, Long.class, Timestamp.from(now.plusSeconds(1)))).isEqualTo(4);
    }

    @Test
    void aSendExceptionRollsBackBudgetAccountingAndHints() {
        Instant now = NOW;
        hint("stock:p1", "{\"productId\":\"p1\"}", now);
        lease("p1", false, null, Duration.ofMinutes(10));
        jdbc.update("insert into public.realtime_test_fail_send (active) values (true)");

        assertThatThrownBy(() -> dispatch(now)).isInstanceOf(RuntimeException.class)
                .rootCause().isInstanceOf(SQLException.class).hasMessageContaining("send failed");

        assertThat(budget()).isEqualTo(1000);
        assertThat(jdbc.queryForObject(
                "select count(*) from public.realtime_send_accounting", Long.class)).isZero();
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(sends()).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from public.realtime_signal_outbox where reserved_message_cost is not null
                """, Long.class)).isZero();
    }

    @Test
    void aConcurrentDispatcherCannotSpendTwice() throws Exception {
        Instant now = NOW;
        hint("stock:p1", "{\"productId\":\"p1\"}", now);
        lease("p1", false, null, Duration.ofMinutes(10));

        assertThat(concurrentDispatches(now)).containsExactlyInAnyOrder(1, 0);
        assertThat(sends()).isEqualTo(1);
        assertThat(budget()).isEqualTo(998);
        assertThat(accountedCost()).isEqualTo(2);
        assertThat(pendingHints()).isZero();
    }

    @Test
    void concurrentDispatchersCannotExceedTheRemainingBudget() throws Exception {
        control(true, 2);
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);
        hint("stock:p2", "{\"productId\":\"p2\"}", NOW);
        lease("p1", false, null, Duration.ofMinutes(10));
        lease("p2", false, null, Duration.ofMinutes(10));

        assertThat(concurrentDispatches(NOW)).containsExactlyInAnyOrder(1, 0);

        assertThat(sends()).isEqualTo(1);
        assertThat(budget()).isZero();
        assertThat(accountedCost()).isEqualTo(2);
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from public.realtime_signal_outbox
                 where sent_at is null and reserved_message_cost is null
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentDispatchersCannotResetTheSharedRollingWindow() throws Exception {
        jdbc.update("""
                insert into public.realtime_signal_outbox (topic, event_name, payload, created_at)
                select 'stock:parallel-' || n, 'stock.changed',
                       jsonb_build_object('productId', 'parallel-' || n), ?
                  from generate_series(1, 31) n
                """, Timestamp.from(NOW));
        jdbc.update("""
                insert into public.realtime_token_leases
                    (jti, admitted_product_ids, expires_at, retire_at)
                select md5('parallel-' || n)::uuid, jsonb_build_array('parallel-' || n), ?, ?
                  from generate_series(1, 31) n
                """, Timestamp.from(NOW.plusSeconds(600)), Timestamp.from(NOW.plusSeconds(630)));

        assertThat(concurrentDispatches(NOW)).containsExactlyInAnyOrder(30, 0);

        assertThat(sends()).isEqualTo(30);
        assertThat(accountedCost()).isEqualTo(60);
        assertThat(budget()).isEqualTo(940);
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(dispatch(NOW)).isZero();
        assertThat(accountedCost()).isEqualTo(60);
        assertThat(dispatch(NOW.plusSeconds(1))).isEqualTo(1);
        assertThat(sends()).isEqualTo(31);
        assertThat(accountedCost()).isEqualTo(62);
        assertThat(budget()).isEqualTo(938);
        assertThat(pendingHints()).isZero();
    }

    @Test
    void expiredButUnretiredLeasesStillCountWhileRetiredAndUnadmittedLeasesDoNot() {
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);
        lease("p1", false, null, Duration.ofSeconds(-10));
        lease("p1", false, null, Duration.ofSeconds(-30));
        lease("p2", false, null, Duration.ofMinutes(10));
        lease(null, true, inboxUserId, Duration.ofMinutes(10));

        assertThat(dispatch(NOW)).isEqualTo(1);

        assertThat(sends()).isEqualTo(1);
        assertThat(accountedCost()).isEqualTo(2);
        assertThat(budget()).isEqualTo(998);
        assertThat(pendingHints()).isZero();
    }

    @Test
    void unadmittedStockAndDeniedInboxLeasesCannotInventRecipients() {
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);
        hint("inbox:" + inboxUserId, "{\"revision\":1}", NOW);
        lease("p2", false, null, Duration.ofMinutes(10));
        lease(null, false, inboxUserId, Duration.ofMinutes(10));
        lease(null, true, inboxUserId, Duration.ofSeconds(-30));

        assertThat(dispatch(NOW)).isZero();

        assertThat(sends()).isZero();
        assertThat(budget()).isEqualTo(1000);
        assertThat(accountedCost()).isZero();
        assertThat(pendingHints()).isZero();
        assertThat(jdbc.queryForList("""
                select reserved_message_cost from public.realtime_signal_outbox order by id
                """, Integer.class)).containsExactly(0, 0);
    }

    @Test
    void unavailableControlNeverSendsOrSpends() {
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);
        lease("p1", false, null, Duration.ofMinutes(10));
        jdbc.update("delete from public.app_realtime_control where id = 1");
        try {
            assertThat(dispatch(NOW)).isZero();
            assertThat(sends()).isZero();
            assertThat(accountedCost()).isZero();
            assertThat(pendingHints()).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    select count(*) from public.app_realtime_control
                    """, Long.class)).isZero();
        } finally {
            jdbc.update("insert into public.app_realtime_control (id) values (1)");
        }
    }

    @Test
    void aTopicCostAboveTheWholeWindowIsNeverSplit() {
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);
        jdbc.update("""
                insert into public.realtime_token_leases
                    (jti, admitted_product_ids, expires_at, retire_at)
                select md5('oversized-' || n)::uuid, '["p1"]'::jsonb, ?, ?
                  from generate_series(1, 60) n
                """, Timestamp.from(NOW.plusSeconds(600)), Timestamp.from(NOW.plusSeconds(630)));

        assertThat(dispatch(NOW)).isZero();
        assertThat(dispatch(NOW.plusSeconds(1))).isZero();

        assertThat(sends()).isZero();
        assertThat(accountedCost()).isZero();
        assertThat(budget()).isEqualTo(1000);
        assertThat(pendingHints()).isEqualTo(1);
    }

    @Test
    void staleAndFreshWorkUsesOnlyOneHundredCapturedUnlockedIds() throws Exception {
        long lockedId = hint("stock:stale", "{\"productId\":\"stale\"}", NOW.minusSeconds(31));
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);
        lease("p1", false, null, Duration.ofMinutes(10));
        jdbc.update("""
                insert into public.realtime_signal_outbox (topic, event_name, payload, created_at)
                select 'stock:stale', 'stock.changed', '{"productId":"stale"}'::jsonb, ?
                  from generate_series(1, 149)
                """, Timestamp.from(NOW.minusSeconds(31)));
        try (Connection locker = connect(); Connection dispatcher = connect()) {
            locker.setAutoCommit(false);
            dispatcher.setAutoCommit(false);
            try {
                try (PreparedStatement statement = locker.prepareStatement(
                        "select id from public.realtime_signal_outbox where id = ? for update")) {
                    statement.setLong(1, lockedId);
                    statement.executeQuery().close();
                }
                try (Statement statement = dispatcher.createStatement()) {
                    statement.execute("set local lock_timeout = '500ms'; set local statement_timeout = '20s'");
                }

                assertThat(dispatch(dispatcher, NOW)).isEqualTo(1);
                dispatcher.commit();

                assertThat(jdbc.queryForObject("""
                        select count(*) from public.realtime_signal_outbox where sent_at is not null
                        """, Long.class)).isEqualTo(100);
                assertThat(jdbc.queryForObject("""
                        select count(*) from public.realtime_signal_outbox
                         where sent_at is not null and reserved_message_cost = 0
                        """, Long.class)).isEqualTo(99);
                assertThat(jdbc.queryForObject("""
                        select count(*) from public.realtime_signal_outbox
                         where id = ? and sent_at is null and reserved_message_cost is null
                        """, Long.class, lockedId)).isEqualTo(1);
                assertThat(pendingHints()).isEqualTo(51);
                assertThat(sends()).isEqualTo(1);
                assertThat(accountedCost()).isEqualTo(2);
                assertThat(budget()).isEqualTo(998);
            } finally {
                dispatcher.rollback();
                locker.rollback();
            }
        }
    }

    @Test
    void aLaterSendFailureRollsBackEarlierSendsAndStaleCleanupThenCanRetry() {
        hint("stock:a", "{\"productId\":\"a\"}", NOW);
        hint("stock:z", "{\"productId\":\"z\"}", NOW);
        hint("stock:stale", "{\"productId\":\"stale\"}", NOW.minusSeconds(31));
        lease("a", false, null, Duration.ofMinutes(10));
        lease("z", false, null, Duration.ofMinutes(10));
        jdbc.update("insert into public.realtime_test_fail_send (active, topic) values (true, 'stock:z')");
        Timestamp originalUpdate = jdbc.queryForObject(
                "select updated_at from public.app_realtime_control where id = 1", Timestamp.class);

        assertThatThrownBy(() -> dispatch(NOW)).isInstanceOf(RuntimeException.class)
                .rootCause().isInstanceOf(SQLException.class)
                .hasMessageContaining("send failed after earlier capture");

        assertThat(budget()).isEqualTo(1000);
        assertThat(jdbc.queryForObject("""
                select updated_at from public.app_realtime_control where id = 1
                """, Timestamp.class)).isEqualTo(originalUpdate);
        assertThat(accountedCost()).isZero();
        assertThat(sends()).isZero();
        assertThat(pendingHints()).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from public.realtime_signal_outbox where reserved_message_cost is not null
                """, Long.class)).isZero();

        jdbc.update("delete from public.realtime_test_fail_send");
        assertThat(dispatch(NOW)).isEqualTo(2);
        assertThat(sends()).isEqualTo(2);
        assertThat(accountedCost()).isEqualTo(4);
        assertThat(budget()).isEqualTo(996);
        assertThat(pendingHints()).isZero();
    }

    @Test
    void dispatchAndPausedStaleCleanupNeverChangePendingDurableOrderEvents() {
        control(false, 1000);
        long orderId = jdbc.queryForObject("""
                insert into public.orders (order_id, customer_id, order_total, order_address,
                                           order_recipient_name, order_phone, event_sequence)
                select 'task15-order', id, 1, 'Scratch address', 'Scratch recipient', '0000000000', 1
                  from public.customers where customer_id = 'customer-task15'
                returning id
                """, Long.class);
        long eventId = jdbc.queryForObject("""
                insert into public.event_outbox (event_id, order_id, order_sequence, event_type,
                                                 schema_version, payload, created_at, available_at)
                values (?, ?, 1, 'ORDER_CREATED', 1, '{"fixture":"durable"}'::jsonb, ?, ?)
                returning id
                """, Long.class, UUID.randomUUID(), orderId,
                Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.minusSeconds(3600)));
        String before = jdbc.queryForObject("""
                select to_jsonb(e)::text from public.event_outbox e where id = ?
                """, String.class, eventId);
        hint("stock:stale", "{\"productId\":\"stale\"}", NOW.minusSeconds(31));
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);
        lease("p1", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(NOW)).isZero();
        assertThat(sends()).isZero();
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select to_jsonb(e)::text from public.event_outbox e where id = ?
                """, String.class, eventId)).isEqualTo(before);
        control(true, 1000);
        assertThat(dispatch(NOW)).isEqualTo(1);
        assertThat(pendingHints()).isZero();
        assertThat(jdbc.queryForObject("""
                select to_jsonb(e)::text from public.event_outbox e where id = ?
                """, String.class, eventId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                select count(*) from public.event_outbox
                 where id = ? and published_at is null and quarantined_at is null
                """, Long.class, eventId)).isEqualTo(1);
    }

    @Test
    void inboxRecipientCostCountsEachAllowedLeaseForOnlyTheTargetUser() {
        long otherUserId = jdbc.queryForObject(
                "select id from public.users where user_id = 'user-task15-other'", Long.class);
        hint("inbox:" + inboxUserId, "{\"revision\":1,\"privateExtra\":\"discarded\"}", NOW);
        lease(null, true, inboxUserId, Duration.ofMinutes(10));
        lease(null, true, inboxUserId, Duration.ofSeconds(-10));
        lease(null, true, inboxUserId, Duration.ofSeconds(-30));
        lease(null, false, inboxUserId, Duration.ofMinutes(10));
        lease(null, true, otherUserId, Duration.ofMinutes(10));
        lease(null, true, null, Duration.ofMinutes(10));

        assertThat(dispatch(NOW)).isEqualTo(1);

        assertThat(sends()).isEqualTo(1);
        assertThat(accountedCost()).isEqualTo(3);
        assertThat(budget()).isEqualTo(997);
        assertThat(pendingHints()).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from public.realtime_test_capture
                 where topic = ? and event = 'inbox.changed' and private
                   and payload = '{"revision":1}'::jsonb
                """, Long.class, "inbox:" + inboxUserId)).isEqualTo(1);
    }

    @Test
    void stockAndInboxRecipientsCountUntilTheExactRetirementBoundary() {
        Instant retirement = NOW.plusSeconds(30);
        Instant justBefore = retirement.minusNanos(1000);
        lease("p1", true, inboxUserId, Duration.ZERO);
        hint("stock:p1", "{\"productId\":\"p1\"}", justBefore);
        hint("inbox:" + inboxUserId, "{\"revision\":1}", justBefore);

        assertThat(dispatch(justBefore)).isEqualTo(2);
        assertThat(sends()).isEqualTo(2);
        assertThat(accountedCost()).isEqualTo(4);
        assertThat(budget()).isEqualTo(996);

        hint("stock:p1", "{\"productId\":\"p1\"}", retirement);
        hint("inbox:" + inboxUserId, "{\"revision\":2}", retirement);
        assertThat(dispatch(retirement)).isZero();

        assertThat(sends()).isEqualTo(2);
        assertThat(accountedCost()).isEqualTo(4);
        assertThat(budget()).isEqualTo(996);
        assertThat(pendingHints()).isZero();
        assertThat(jdbc.queryForList("""
                select reserved_message_cost from public.realtime_signal_outbox order by id
                """, Integer.class)).containsExactly(2, 2, 0, 0);
    }

    @Test
    void aFreshConnectionAndLaterWindowsNeverRefillTheOperatorBudget() throws Exception {
        control(true, 4);
        lease("p1", false, null, Duration.ofMinutes(10));
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW);

        assertThat(dispatch(NOW)).isEqualTo(1);
        assertThat(budget()).isEqualTo(2);
        assertThat(accountedCost()).isEqualTo(2);

        try (Connection restarted = connect()) {
            hint("stock:p1", "{\"productId\":\"p1\"}", NOW.plusSeconds(1));
            assertThat(dispatch(restarted, NOW.plusSeconds(1))).isEqualTo(1);
            assertThat(budget()).isZero();
            assertThat(accountedCost()).isEqualTo(4);

            hint("stock:p1", "{\"productId\":\"p1\"}", NOW.plusSeconds(2));
            assertThat(dispatch(restarted, NOW.plusSeconds(2))).isZero();
        }

        assertThat(sends()).isEqualTo(2);
        assertThat(budget()).isZero();
        assertThat(accountedCost()).isEqualTo(4);
        assertThat(pendingHints()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from public.realtime_signal_outbox
                 where sent_at is null and reserved_message_cost is null
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void theThirtySecondCutoffIsStaleButOneMicrosecondNewerCanSend() {
        hint("stock:p1", "{\"productId\":\"p1\"}", NOW.minusSeconds(30));
        hint("stock:p2", "{\"productId\":\"p2\"}", NOW.minusSeconds(30).plusNanos(1000));
        lease("p1", false, null, Duration.ofMinutes(10));
        lease("p2", false, null, Duration.ofMinutes(10));

        assertThat(dispatch(NOW)).isEqualTo(1);

        assertThat(sends()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select topic from public.realtime_test_capture", String.class)).isEqualTo("stock:p2");
        assertThat(accountedCost()).isEqualTo(2);
        assertThat(budget()).isEqualTo(998);
        assertThat(pendingHints()).isZero();
        assertThat(jdbc.queryForList("""
                select reserved_message_cost from public.realtime_signal_outbox order by id
                """, Integer.class)).containsExactly(0, 2);
    }

    private int dispatch(Instant now) {
        Integer sent = jdbc.queryForObject(
                "select app_private.dispatch_realtime_signals(?)", Integer.class,
                Timestamp.from(now));
        return sent == null ? 0 : sent;
    }

    private void control(boolean enabled, long budget) {
        jdbc.update("""
                update public.app_realtime_control
                   set enabled = ?, remaining_message_budget = ?, updated_at = ?
                  where id = 1
                """, enabled, budget, Timestamp.from(NOW.minusSeconds(1)));
    }

    private long hint(String topic, String payload, Instant createdAt) {
        return jdbc.queryForObject("""
                insert into public.realtime_signal_outbox
                    (topic, event_name, payload, created_at)
                values (?, ?, ?::jsonb, ?) returning id
                """, Long.class, topic, topic.startsWith("inbox:") ? "inbox.changed" : "stock.changed",
                payload, Timestamp.from(createdAt));
    }

    private void lease(String productId, boolean inbox, Long userId, Duration lifetime) {
        Instant expiresAt = NOW.plus(lifetime);
        jdbc.update("""
                insert into public.realtime_token_leases
                    (jti, user_id, admitted_product_ids, inbox_allowed, expires_at, retire_at)
                values (?, ?, ?::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), userId,
                productId == null ? "[]" : "[\"" + productId + "\"]",
                inbox, Timestamp.from(expiresAt), Timestamp.from(expiresAt.plusSeconds(30)));
    }

    private long sends() {
        return jdbc.queryForObject(
                "select count(*) from public.realtime_test_capture", Long.class);
    }

    private long accountedCost() {
        return jdbc.queryForObject("""
                select coalesce(sum(reserved_message_cost), 0) from public.realtime_send_accounting
                """, Long.class);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private List<Integer> concurrentDispatches(Instant now) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection locker = connect(); Connection monitor = connect();
             Connection first = connect(); Connection second = connect()) {
            locker.setAutoCommit(false);
            try {
                for (Connection connection : List.of(first, second)) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("set statement_timeout = '60s'");
                    }
                }
                long blockerPid = backendPid(locker);
                long firstPid = backendPid(first);
                long secondPid = backendPid(second);
                try (Statement statement = locker.createStatement()) {
                    statement.executeQuery("""
                            select id from public.app_realtime_control where id = 1 for update
                            """).close();
                }
                Future<Integer> firstCall = executor.submit(() -> dispatch(first, now));
                Future<Integer> secondCall = executor.submit(() -> dispatch(second, now));
                boolean bothBlocked = false;
                String waitChain = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                // Row-lock waiters can queue behind each other, not directly behind the lock holder.
                try (PreparedStatement statement = monitor.prepareStatement("""
                        with recursive waits(origin_pid, waiting_pid, blocking_pid) as (
                            select a.pid, a.pid, b.pid
                              from pg_stat_activity a
                              cross join lateral unnest(pg_blocking_pids(a.pid)) b(pid)
                             where a.pid in (?, ?) and a.wait_event_type = 'Lock'
                            union
                            select w.origin_pid, w.blocking_pid, b.pid
                              from waits w
                              cross join lateral unnest(pg_blocking_pids(w.blocking_pid)) b(pid)
                        )
                        select count(distinct origin_pid) filter (where blocking_pid = ?),
                               string_agg(distinct waiting_pid || ' blocked by ' || blocking_pid, '; ')
                          from waits
                        """)) {
                    statement.setLong(1, firstPid);
                    statement.setLong(2, secondPid);
                    statement.setLong(3, blockerPid);
                    while (System.nanoTime() < deadline && !firstCall.isDone() && !secondCall.isDone()) {
                        try (ResultSet rows = statement.executeQuery()) {
                            rows.next();
                            waitChain = rows.getString(2);
                            if (rows.getInt(1) == 2) {
                                bothBlocked = true;
                                break;
                            }
                        }
                        Thread.sleep(25);
                    }
                }
                assertThat(bothBlocked)
                        .as("Both independent dispatchers must actually wait on our shared control-row lock: %s",
                                waitChain)
                        .isTrue();
                assertThat(firstCall.isDone()).isFalse();
                assertThat(secondCall.isDone()).isFalse();
                locker.commit();
                List<Integer> results = List.of(firstCall.get(65, TimeUnit.SECONDS),
                        secondCall.get(65, TimeUnit.SECONDS));
                System.out.println("Task 15 concurrency: control-rooted waiters=2; " + waitChain
                        + "; results=" + results);
                return results;
            } finally {
                locker.rollback();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(65, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static long backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select pg_backend_pid()")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static int dispatch(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select app_private.dispatch_realtime_signals(?)")) {
            statement.setTimestamp(1, Timestamp.from(now));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private long pendingHints() {
        return jdbc.queryForObject(
                "select count(*) from public.realtime_signal_outbox where sent_at is null",
                Long.class);
    }

    private long budget() {
        return jdbc.queryForObject(
                "select remaining_message_budget from public.app_realtime_control where id = 1",
                Long.class);
    }
}
