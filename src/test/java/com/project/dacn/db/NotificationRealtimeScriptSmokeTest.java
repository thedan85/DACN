package com.project.dacn.db;

import com.project.dacn.support.ExternalPostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationRealtimeScriptSmokeTest {

    private static final String STUBS = """
            create schema if not exists realtime;
            create table if not exists realtime.messages (
                id bigint generated always as identity primary key,
                extension text,
                topic text,
                event text,
                payload jsonb,
                private boolean not null default true
            );
            create table if not exists public.realtime_test_capture (
                topic text,
                event text,
                payload jsonb,
                private boolean,
                captured_at timestamptz not null default clock_timestamp()
            );
            create schema if not exists auth;
            create function auth.jwt() returns jsonb
                language sql stable set search_path = '' as $$
                    select nullif(current_setting('request.jwt.claims', true), '')::jsonb
                $$;
            create or replace function realtime.topic() returns text
                language sql stable as $$
                    select coalesce(current_setting('realtime.topic', true), '')
                $$;
            create or replace function realtime.send(
                    payload jsonb, event text, topic text, private boolean)
            returns void language sql as $$
                insert into public.realtime_test_capture (topic, event, payload, private)
                values (topic, event, payload, private)
            $$;
            do $$
            begin
                if not exists (select 1 from pg_roles where rolname = 'anon')
                   or not exists (select 1 from pg_roles where rolname = 'authenticated') then
                    raise exception 'Scratch smoke test requires existing anon and authenticated roles; never create cluster roles';
                end if;
            end $$;
            -- This table is an invocation-owned stub, not Supabase's managed table.
            alter table realtime.messages enable row level security;
            grant usage on schema realtime to anon, authenticated;
            grant select, insert, update, delete on realtime.messages to anon, authenticated;
            grant usage on sequence realtime.messages_id_seq to anon, authenticated;
            revoke all on public.users from anon, authenticated;
            insert into realtime.messages (extension, topic, event, payload)
            values ('broadcast', 'authorization-probe', 'probe', '{}'),
                   ('presence', 'authorization-probe', 'probe', '{}');
            """;

    private static ExternalPostgresDatabase database;
    private static String script;
    private static String scratchName;

    @BeforeAll
    static void setUp() throws Exception {
        database = ExternalPostgresDatabase.create();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select current_database()")) {
            rows.next();
            scratchName = rows.getString(1);
            assertThat(scratchName).startsWith("verify_event_foundation_");
        }
        database.migrate();
        script = Files.readString(
                Path.of("src/main/resources/db/supabase/notification_realtime.sql"));
        try (Connection connection = connect()) {
            execute(connection, STUBS);
            execute(connection, script);
            execute(connection, script);
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
                    rows.next();
                    assertThat(rows.getLong(1)).as("Only this invocation's scratch database is removed").isZero();
                }
            }
            System.out.println("Task 14 scratch cleanup: " + scratchName + "; remaining=0");
        }
    }

    @Test
    @Order(1)
    void receivePoliciesExistAndNoClientWritePolicyDoes() throws Exception {
        try (Connection connection = connect()) {
            assertThat(policyCommand(connection, "dacn realtime stock receive"))
                    .isEqualTo("SELECT");
            assertThat(policyCommand(connection, "dacn realtime inbox receive"))
                    .isEqualTo("SELECT");
            assertThat(count(connection, """
                    select count(*) from pg_policies
                     where schemaname = 'realtime' and tablename = 'messages'
                       and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
                    """)).isZero();
        }
    }

    @Test
    @Order(2)
    void inboxHelperRequiresAnActiveExistingAccount() throws Exception {
        long active = insertUser("ACTIVE");
        long locked = insertUser("LOCKED");
        long deleted = insertUser("DELETED");

        try (Connection connection = connect()) {
            assertThat(inboxAllowed(connection, Long.toString(active))).isTrue();
            assertThat(inboxAllowed(connection, Long.toString(locked))).isFalse();
            assertThat(inboxAllowed(connection, Long.toString(deleted))).isFalse();
            assertThat(inboxAllowed(connection, "999999999")).isFalse();
            assertThat(inboxAllowed(connection, null)).isFalse();
        }
    }

    @Test
    @Order(3)
    void dispatcherIsExecutableByTheApplyingRoleOnly() throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select has_function_privilege(
                                current_user,
                                'app_private.dispatch_realtime_signals(timestamptz)',
                                'EXECUTE'),
                            (select count(*)
                               from pg_proc p
                               cross join lateral aclexplode(
                                       coalesce(p.proacl, acldefault('f', p.proowner))) a
                              where p.oid = 'app_private.dispatch_realtime_signals(timestamptz)'::regprocedure
                                and a.grantee = 0
                                and a.privilege_type = 'EXECUTE')
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBoolean(1)).isTrue();
            assertThat(rows.getLong(2)).isZero();
        }
        try (Connection connection = connect()) {
            assertRestrictedFunctions(connection);
        }
    }

    @Test
    @Order(4)
    void reapplyRemovesExistingFunctionGrantsIncludingNonBrowserGrantees() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        grant execute on function app_private.realtime_inbox_allowed(text)
                            to anon, pg_read_all_data;
                        grant execute on function app_private.send_realtime_signal(text, text, jsonb),
                            app_private.dispatch_realtime_signals(timestamptz)
                            to anon, authenticated, pg_read_all_data;
                        """);
                execute(connection, script);
                assertRestrictedFunctions(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Order(4)
    void installationRemovesDefaultFunctionGrantsWithoutChangingDefaults() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        alter default privileges in schema app_private
                            grant execute on functions to anon, authenticated, pg_read_all_data;
                        drop function app_private.dispatch_realtime_signals(timestamptz);
                        drop function app_private.send_realtime_signal(text, text, jsonb);
                        drop function app_private.realtime_inbox_allowed(text) cascade;
                        """);
                long defaults = count(connection, """
                        select count(*) from pg_default_acl d
                        cross join lateral aclexplode(d.defaclacl) a
                        where d.defaclnamespace = 'app_private'::regnamespace
                          and a.privilege_type = 'EXECUTE'
                        """);
                execute(connection, script);
                assertRestrictedFunctions(connection);
                assertThat(count(connection, """
                        select count(*) from pg_default_acl d
                        cross join lateral aclexplode(d.defaclacl) a
                        where d.defaclnamespace = 'app_private'::regnamespace
                          and a.privilege_type = 'EXECUTE'
                        """)).isEqualTo(defaults);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Order(5)
    void stockReceiveRequiresAnExactAdmittedClaimAndDeniesPresence() throws Exception {
        for (String role : new String[]{"anon", "authenticated"}) {
            assertThat(visibleRows(role, "stock:p1", "{\"stock_product_ids\":[\"p1\"]}", "broadcast"))
                    .isEqualTo(1);
            assertThat(visibleRows(role, "stock:p2", "{\"stock_product_ids\":[\"p1\"]}", "broadcast"))
                    .isZero();
            assertThat(visibleRows(role, "stock:p1", "{}", "broadcast")).isZero();
            assertThat(visibleRows(role, "stock:p1", "", "broadcast")).isZero();
            assertThat(visibleRows(role, "stock:p123", "{\"stock_product_ids\":[\"p_%\"]}", "broadcast"))
                    .isZero();
            assertThat(visibleRows(role, "stock:p1", "{\"stock_product_ids\":[\"p1\"]}", "presence"))
                    .isZero();
        }
    }

    @Test
    @Order(5)
    void inboxReceiveRequiresSignedOwnActiveAccountAndDeniesGuestsAndPresence() throws Exception {
        long active = insertUser("ACTIVE");
        long locked = insertUser("LOCKED");
        long deleted = insertUser("DELETED");
        String claims = "{\"app_user_id\":\"" + active + "\",\"inbox_allowed\":true}";
        assertThat(visibleRows("authenticated", "inbox:" + active, claims, "broadcast")).isEqualTo(1);
        assertThat(visibleRows("authenticated", "inbox:" + (active + 1), claims, "broadcast")).isZero();
        assertThat(visibleRows("anon", "inbox:" + active, claims, "broadcast")).isZero();
        assertThat(visibleRows("authenticated", "inbox:" + active,
                claims.replace("true", "false"), "broadcast")).isZero();
        assertThat(visibleRows("authenticated", "inbox:" + active,
                "{\"app_user_id\":\"" + active + "\"}", "broadcast")).isZero();
        assertThat(visibleRows("authenticated", "inbox:" + active,
                "{\"inbox_allowed\":true}", "broadcast")).isZero();
        for (long denied : new long[]{locked, deleted, 999999999}) {
            assertThat(visibleRows("authenticated", "inbox:" + denied,
                    "{\"app_user_id\":\"" + denied + "\",\"inbox_allowed\":true}", "broadcast"))
                    .isZero();
        }
        assertThat(visibleRows("authenticated", "inbox:" + active, claims, "presence")).isZero();
    }

    @Test
    @Order(6)
    void clientsCannotWriteBroadcastOrPresenceOrReadUsers() throws Exception {
        for (String role : new String[]{"anon", "authenticated"}) {
            for (String extension : new String[]{"broadcast", "presence"}) {
                try (Connection connection = connect()) {
                    connection.setAutoCommit(false);
                    try {
                        clientContext(connection, role, "stock:p1", "{\"stock_product_ids\":[\"p1\"]}");
                        assertThatThrownBy(() -> execute(connection,
                                "insert into realtime.messages (extension, topic) values ('"
                                        + extension + "', 'stock:p1')"))
                                .isInstanceOf(SQLException.class)
                                .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("42501");
                    } finally {
                        connection.rollback();
                    }
                }
            }
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    clientContext(connection, role, "stock:p1", "{\"stock_product_ids\":[\"p1\"]}");
                    try (Statement statement = connection.createStatement()) {
                        assertThat(statement.executeUpdate("update realtime.messages set event = 'client'"))
                                .isZero();
                        assertThat(statement.executeUpdate("delete from realtime.messages")).isZero();
                    }
                    assertThatThrownBy(() -> count(connection, "select count(*) from public.users"))
                            .isInstanceOf(SQLException.class)
                            .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("42501");
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    @Order(7)
    void conflictingPermissivePoliciesBlockReapplyWithoutBeingRemoved() throws Exception {
        for (String clause : new String[]{
                "for select to public using (true)",
                "for insert to authenticated with check (true)",
                "for all to anon using (true) with check (true)"}) {
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    execute(connection, "create policy \"foreign permissive smoke\" on realtime.messages " + clause);
                    Savepoint beforeScript = connection.setSavepoint();
                    assertThatThrownBy(() -> execute(connection, script))
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("Conflicting Realtime policy");
                    connection.rollback(beforeScript);
                    assertThat(count(connection, """
                            select count(*) from pg_policies where schemaname = 'realtime'
                            and tablename = 'messages' and policyname = 'foreign permissive smoke'
                            """)).isEqualTo(1);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    @Order(7)
    void reapplyPreservesUnrelatedPoliciesOwnershipRlsAndControlState() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        create policy "foreign restrictive smoke" on realtime.messages
                            as restrictive for select to anon using (true);
                        create policy "foreign backend smoke" on realtime.messages
                            for select to current_user using (true);
                        update public.app_realtime_control set enabled = true, remaining_message_budget = 17;
                        """);
                long owner = count(connection,
                        "select relowner from pg_class where oid = 'realtime.messages'::regclass");
                execute(connection, script);
                execute(connection, script);
                assertThat(count(connection, """
                        select count(*) from pg_policies where schemaname = 'realtime'
                        and tablename = 'messages'
                        and policyname in ('foreign restrictive smoke', 'foreign backend smoke')
                        """)).isEqualTo(2);
                assertThat(count(connection,
                        "select relowner from pg_class where oid = 'realtime.messages'::regclass"))
                        .isEqualTo(owner);
                assertThat(count(connection, """
                        select count(*) from pg_class where oid = 'realtime.messages'::regclass
                        and relrowsecurity and not relforcerowsecurity
                        """)).isEqualTo(1);
                assertThat(count(connection, """
                        select count(*) from public.app_realtime_control
                        where id = 1 and enabled and remaining_message_budget = 17
                        """)).isEqualTo(1);
                assertThat(count(connection, """
                        select count(*) from information_schema.columns
                        where table_schema = 'public' and table_name = 'users' and column_name = 'inbox_revision'
                        """)).isZero();
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Order(8)
    void missingFoundationObjectsAndRealtimeFunctionsFailBeforeInstallation() throws Exception {
        String[][] missing = {
                {"alter table public.app_realtime_control rename to smoke_missing", "app_realtime_control missing"},
                {"alter table public.users rename to smoke_missing", "users missing"},
                {"alter table public.realtime_token_leases rename to smoke_missing", "realtime_token_leases missing"},
                {"alter table public.realtime_signal_outbox rename to smoke_missing", "realtime_signal_outbox missing"},
                {"alter table public.realtime_send_accounting rename to smoke_missing", "realtime_send_accounting missing"},
                {"alter function realtime.topic() rename to smoke_missing", "realtime.topic() missing"},
                {"alter function realtime.send(jsonb, text, text, boolean) rename to smoke_missing", "realtime.send(jsonb,text,text,boolean) missing"},
                {"alter table public.users rename column user_status to smoke_missing", "users.user_status missing"},
                {"alter table realtime.messages rename column extension to smoke_missing", "realtime.messages.extension missing"},
                {"delete from public.app_realtime_control where id = 1", "app_realtime_control row id=1 missing"}
        };
        for (String[] prerequisite : missing) {
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    execute(connection, prerequisite[0]);
                    assertThatThrownBy(() -> execute(connection, script))
                            .as(prerequisite[1]).isInstanceOf(SQLException.class)
                            .hasMessageContaining(prerequisite[1]);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    @Order(8)
    void dispatchIsDisabledOrBudgetedAndAlwaysSendsPrivately() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                dispatchFixture(connection);
                execute(connection, "update public.app_realtime_control set enabled = false");
                assertThat(count(connection, "select app_private.dispatch_realtime_signals('2026-11-04T00:00:00Z')"))
                        .isZero();
                execute(connection, "update public.app_realtime_control set enabled = true, remaining_message_budget = 0");
                assertThat(count(connection, "select app_private.dispatch_realtime_signals('2026-11-04T00:00:00Z')"))
                        .isZero();
                execute(connection, "update public.app_realtime_control set remaining_message_budget = 1");
                assertThat(count(connection, "select app_private.dispatch_realtime_signals('2026-11-04T00:00:00Z')"))
                        .isZero();
                assertThat(count(connection, "select count(*) from public.realtime_test_capture")).isZero();
                execute(connection, "update public.app_realtime_control set remaining_message_budget = 100");
                assertThat(count(connection, "select app_private.dispatch_realtime_signals('2026-11-04T00:00:00Z')"))
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from public.realtime_test_capture where private"))
                        .isEqualTo(1);
                assertThat(count(connection, "select remaining_message_budget from public.app_realtime_control"))
                        .isEqualTo(98);
                assertThat(count(connection, "select sum(reserved_message_cost) from public.realtime_send_accounting"))
                        .isEqualTo(2);
                assertThat(count(connection, """
                        select count(*) from public.realtime_send_accounting a
                        join public.app_realtime_control c on c.id = 1
                        join public.realtime_signal_outbox h on h.topic = a.topic
                        where a.sent_at = '2026-11-04T00:00:00Z' and c.updated_at = a.sent_at
                          and h.sent_at = a.sent_at
                        """)).isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Order(8)
    void dispatchSkipsLockedHintsWithoutMarkingThemProcessed() throws Exception {
        try (Connection connection = connect(); Connection locker = connect()) {
            dispatchFixture(connection);
            execute(connection, """
                    insert into public.realtime_signal_outbox (topic, event_name, payload, created_at)
                    select 'stock:p2', 'stock.changed', '{"productId":"p2"}'::jsonb, '2026-11-04T00:00:00Z'::timestamptz
                    from generate_series(1, 101);
                    """);
            locker.setAutoCommit(false);
            connection.setAutoCommit(false);
            try {
                execute(locker, "select id from public.realtime_signal_outbox where topic = 'stock:p1' for update");
                execute(connection, "set local lock_timeout = '500ms'");
                assertThatCode(() -> assertThat(count(connection,
                        "select app_private.dispatch_realtime_signals('2026-11-04T00:00:00Z')")).isEqualTo(1))
                        .doesNotThrowAnyException();
                assertThat(count(connection, """
                        select count(*) from public.realtime_signal_outbox
                        where topic = 'stock:p1' and sent_at is null
                        """)).isEqualTo(1);
                assertThat(count(connection, "select count(*) from public.realtime_test_capture where topic = 'stock:p2'"))
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from public.realtime_signal_outbox where sent_at is not null"))
                        .isEqualTo(100);
                assertThat(count(connection, """
                        select count(*) from public.realtime_signal_outbox
                        where topic = 'stock:p2' and sent_at is null
                        """)).isEqualTo(1);
            } finally {
                connection.rollback();
                locker.rollback();
                execute(connection, """
                        delete from public.realtime_signal_outbox;
                        delete from public.realtime_token_leases;
                        update public.app_realtime_control set enabled = false, remaining_message_budget = 0;
                        """);
                connection.commit();
            }
        }
    }

    @Test
    @Order(8)
    void defaultDispatchSamplesOneClockAfterTheActualControlLockWait() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection monitor = connect(); Connection locker = connect(); Connection dispatcher = connect()) {
            execute(monitor, """
                    update public.app_realtime_control set enabled = true, remaining_message_budget = 1000;
                    insert into public.realtime_token_leases
                        (jti, admitted_product_ids, inbox_allowed, expires_at, retire_at)
                    select md5('clock-' || topic || '-' || receiver)::uuid,
                           jsonb_build_array('clock-' || topic), false,
                           clock_timestamp() + interval '5 minutes', clock_timestamp() + interval '5 minutes 30 seconds'
                      from generate_series(1, 6) topic cross join generate_series(1, 19) receiver;
                    insert into public.realtime_signal_outbox (topic, event_name, payload)
                    select 'stock:clock-' || topic, 'stock.changed', jsonb_build_object('productId', 'clock-' || topic)
                      from generate_series(1, 6) topic;
                    """);
            locker.setAutoCommit(false);
            dispatcher.setAutoCommit(false);
            try {
                long blockerPid = count(locker, "select pg_backend_pid()");
                long dispatcherPid = count(dispatcher, "select pg_backend_pid()");
                execute(dispatcher, """
                        set local statement_timeout = '20s';
                        create temporary table task14_clock_probe (first_count integer, second_count integer);
                        """);
                execute(locker, "select id from public.app_realtime_control where id = 1 for update");
                Future<?> work = executor.submit(() -> {
                    execute(dispatcher, """
                            do $$
                            declare first_count integer; second_count integer;
                            begin
                                first_count := app_private.dispatch_realtime_signals();
                                second_count := app_private.dispatch_realtime_signals();
                                insert into pg_temp.task14_clock_probe values (first_count, second_count);
                            end $$;
                            """);
                    return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                while (System.nanoTime() < deadline && !work.isDone()) {
                    blocked = count(monitor, "select count(*) from pg_stat_activity where pid = " + dispatcherPid
                            + " and wait_event_type = 'Lock' and " + blockerPid + " = any(pg_blocking_pids(pid))") == 1;
                    if (blocked) {
                        break;
                    }
                    Thread.sleep(25);
                }
                assertThat(blocked).as("The no-argument call must actually wait for our control lock").isTrue();
                execute(locker, "select pg_sleep(1.2)");
                Instant releaseLowerBound;
                try (Statement statement = locker.createStatement();
                     ResultSet rows = statement.executeQuery("select clock_timestamp()")) {
                    rows.next();
                    releaseLowerBound = rows.getTimestamp(1).toInstant();
                }
                locker.commit();
                work.get(25, TimeUnit.SECONDS);
                assertThat(count(dispatcher, "select first_count from pg_temp.task14_clock_probe")).isEqualTo(3);
                assertThat(count(dispatcher, "select second_count from pg_temp.task14_clock_probe"))
                        .as("The immediate next default call cannot spend the same rolling window again").isZero();
                try (Statement statement = dispatcher.createStatement();
                     ResultSet rows = statement.executeQuery("select min(sent_at) from public.realtime_send_accounting")) {
                    rows.next();
                    assertThat(rows.getTimestamp(1).toInstant()).isAfterOrEqualTo(releaseLowerBound);
                }
                assertThat(count(dispatcher, "select sum(reserved_message_cost) from public.realtime_send_accounting"))
                        .isEqualTo(60);
                assertThat(count(dispatcher, """
                        select count(*) from public.realtime_send_accounting a
                        join public.realtime_signal_outbox h on h.topic = a.topic
                        join public.app_realtime_control c on c.id = 1
                        where a.sent_at = h.sent_at and a.sent_at = c.updated_at
                        """)).isEqualTo(3);
            } finally {
                locker.rollback();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(25, TimeUnit.SECONDS)).isTrue();
                dispatcher.rollback();
                execute(monitor, """
                        delete from public.realtime_signal_outbox;
                        delete from public.realtime_token_leases;
                        update public.app_realtime_control set enabled = false, remaining_message_budget = 0;
                        """);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Order(8)
    void staleAndFreshHintsShareOneHundredClaimedIds() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                dispatchFixture(connection);
                execute(connection, """
                        insert into public.realtime_signal_outbox (topic, event_name, payload, created_at)
                        select 'stock:p2', 'stock.changed', '{"productId":"p2"}'::jsonb, '2026-11-03T23:59:29Z'::timestamptz
                          from generate_series(1, 150);
                        """);
                assertThat(count(connection, "select app_private.dispatch_realtime_signals('2026-11-04T00:00:00Z')"))
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from public.realtime_signal_outbox where sent_at is not null"))
                        .as("Stale work must not add an unbounded pre-pass to the 100-ID batch").isEqualTo(100);
                assertThat(count(connection, """
                        select count(*) from public.realtime_signal_outbox
                        where sent_at is not null and reserved_message_cost = 0
                        """)).isEqualTo(99);
                assertThat(count(connection, "select count(*) from public.realtime_signal_outbox where sent_at is null"))
                        .isEqualTo(51);
                assertThat(count(connection, "select count(*) from public.realtime_test_capture")).isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Order(8)
    void pausedAndUnbudgetedStaleCleanupRemainsBounded() throws Exception {
        for (String control : new String[]{"enabled = false, remaining_message_budget = 100",
                "enabled = true, remaining_message_budget = 0"}) {
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    execute(connection, "update public.app_realtime_control set " + control);
                    execute(connection, """
                            insert into public.realtime_signal_outbox (topic, event_name, payload, created_at)
                            select 'stock:p1', 'stock.changed', '{"productId":"p1"}'::jsonb, '2026-11-03T23:59:29Z'::timestamptz
                              from generate_series(1, 151);
                            """);
                    assertThat(count(connection, "select app_private.dispatch_realtime_signals('2026-11-04T00:00:00Z')"))
                            .isZero();
                    assertThat(count(connection, """
                            select count(*) from public.realtime_signal_outbox
                            where sent_at = '2026-11-04T00:00:00Z' and reserved_message_cost = 0
                            """)).isEqualTo(100);
                    assertThat(count(connection, "select count(*) from public.realtime_signal_outbox where sent_at is null"))
                            .isEqualTo(51);
                    assertThat(count(connection, "select count(*) from public.realtime_test_capture")).isZero();
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    @Order(8)
    void pausedCleanupSkipsLockedStaleHintsWithoutProcessingFreshHints() throws Exception {
        try (Connection connection = connect(); Connection locker = connect()) {
            dispatchFixture(connection);
            execute(connection, """
                    update public.app_realtime_control set enabled = false;
                    update public.realtime_signal_outbox set created_at = '2026-11-03T23:59:29Z';
                    insert into public.realtime_signal_outbox (topic, event_name, payload, created_at)
                    values ('stock:p2', 'stock.changed', '{"productId":"p2"}', '2026-11-04T00:00:00Z');
                    """);
            connection.setAutoCommit(false);
            locker.setAutoCommit(false);
            try {
                execute(locker, "select id from public.realtime_signal_outbox where topic = 'stock:p1' for update");
                execute(connection, "set local lock_timeout = '500ms'");
                assertThatCode(() -> assertThat(count(connection,
                        "select app_private.dispatch_realtime_signals('2026-11-04T00:00:00Z')")).isZero())
                        .doesNotThrowAnyException();
                assertThat(count(connection, "select count(*) from public.realtime_signal_outbox where sent_at is null"))
                        .isEqualTo(2);
                assertThat(count(connection, "select count(*) from public.realtime_test_capture")).isZero();
            } finally {
                connection.rollback();
                locker.rollback();
                execute(connection, """
                        delete from public.realtime_signal_outbox;
                        delete from public.realtime_token_leases;
                        update public.app_realtime_control set enabled = false, remaining_message_budget = 0;
                        """);
                connection.commit();
            }
        }
    }

    @Test
    @Order(8)
    void outboundWrapperReconstructsOnlyStockAndInboxInvalidationFields() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                sendSignal(connection, "stock:p1", "stock.changed",
                        "{\"productId\":\"p1\",\"email\":\"fixture@example.invalid\",\"order\":{\"total\":123}}");
                sendSignal(connection, "inbox:7", "inbox.changed",
                        "{\"revision\":9,\"email\":\"fixture@example.invalid\",\"message\":\"private fixture\"}");
                assertThat(count(connection, """
                        select count(*) from public.realtime_test_capture where private and (
                            (topic = 'stock:p1' and event = 'stock.changed' and payload = '{"productId":"p1"}'::jsonb)
                            or (topic = 'inbox:7' and event = 'inbox.changed' and payload = '{"revision":9}'::jsonb))
                        """)).isEqualTo(2);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Order(8)
    void outboundWrapperRejectsInvalidTopicEventAndRequiredPayloadShapes() throws Exception {
        String[][] invalid = {
                {"stock:p1", "order.details", "{\"productId\":\"p1\"}"},
                {"stock:p1", "inbox.changed", "{\"productId\":\"p1\"}"},
                {"public:p1", "stock.changed", "{\"productId\":\"p1\"}"},
                {"stock:", "stock.changed", "{\"productId\":\"\"}"},
                {"stock: ", "stock.changed", "{\"productId\":\" \"}"},
                {"stock:p1", "stock.changed", "{}"},
                {"stock:p1", "stock.changed", "{\"productId\":null}"},
                {"stock:p1", "stock.changed", "{\"productId\":1}"},
                {"stock:p1", "stock.changed", "{\"productId\":\"p2\"}"},
                {"stock:p1", "stock.changed", "[]"},
                {"stock:p1", "stock.changed", "null"},
                {"stock:p1", "stock.changed", null},
                {null, "stock.changed", "{\"productId\":\"p1\"}"},
                {"stock:p1", null, "{\"productId\":\"p1\"}"},
                {"stock:" + "p".repeat(256), "stock.changed", "{\"productId\":\"" + "p".repeat(256) + "\"}"},
                {"inbox:7", "stock.changed", "{\"revision\":1}"},
                {"inbox:other", "inbox.changed", "{\"revision\":1}"},
                {"inbox:7:8", "inbox.changed", "{\"revision\":1}"},
                {"inbox:0", "inbox.changed", "{\"revision\":1}"},
                {"inbox:-7", "inbox.changed", "{\"revision\":1}"},
                {"inbox:07", "inbox.changed", "{\"revision\":1}"},
                {"inbox:9223372036854775808", "inbox.changed", "{\"revision\":1}"},
                {"inbox:7", "inbox.changed", "{}"},
                {"inbox:7", "inbox.changed", "{\"revision\":null}"},
                {"inbox:7", "inbox.changed", "{\"revision\":\"1\"}"},
                {"inbox:7", "inbox.changed", "{\"revision\":true}"},
                {"inbox:7", "inbox.changed", "{\"revision\":-1}"},
                {"inbox:7", "inbox.changed", "{\"revision\":1.5}"},
                {"inbox:7", "inbox.changed", "{\"revision\":9223372036854775808}"}
        };
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                for (String[] signal : invalid) {
                    Savepoint beforeSend = connection.setSavepoint();
                    try {
                        assertThatThrownBy(() -> sendSignal(connection, signal[0], signal[1], signal[2]))
                                .as("Invalid signal for topic %s and event %s", signal[0], signal[1])
                                .isInstanceOf(SQLException.class)
                                .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("22023");
                    } finally {
                        connection.rollback(beforeSend);
                    }
                }
                assertThat(count(connection, "select count(*) from public.realtime_test_capture")).isZero();
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Order(8)
    void outboundWrapperEnforcesTheUtf8ByteLimitOnTheReconstructedSignal() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String exactLimitId = "\uD83D\uDE00".repeat(251) + "abc";
                sendSignal(connection, "stock:" + exactLimitId, "stock.changed",
                        "{\"productId\":\"" + exactLimitId + "\"}");
                assertThat(count(connection, """
                        select octet_length(convert_to(payload::text, 'UTF8')) from public.realtime_test_capture
                        """)).isEqualTo(1024);
                String oversizedId = "\uD83D\uDE00".repeat(252);
                Savepoint beforeSend = connection.setSavepoint();
                assertThatThrownBy(() -> sendSignal(connection, "stock:" + oversizedId, "stock.changed",
                        "{\"productId\":\"" + oversizedId + "\"}"))
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("22023");
                connection.rollback(beforeSend);
                sendSignal(connection, "stock:p1", "stock.changed",
                        "{\"productId\":\"p1\",\"privateExtra\":\"" + "x".repeat(1500) + "\"}");
                assertThat(count(connection, """
                        select count(*) from public.realtime_test_capture
                        where payload = '{"productId":"p1"}'::jsonb
                        """)).isEqualTo(1);
                assertThat(count(connection, "select count(*) from public.realtime_test_capture")).isEqualTo(2);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Order(8)
    void rejectedOutboundHintRollsBackPriorSendsBudgetAccountingAndHintUpdates() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                dispatchFixture(connection);
                execute(connection, """
                        insert into public.realtime_signal_outbox (topic, event_name, payload, created_at)
                        values ('stock:p2', 'order.details', '{"email":"fixture@example.invalid"}', '2026-11-04T00:00:00Z');
                        """);
                Savepoint beforeDispatch = connection.setSavepoint();
                assertThatThrownBy(() -> count(connection,
                        "select app_private.dispatch_realtime_signals('2026-11-04T00:00:00Z')"))
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("22023");
                connection.rollback(beforeDispatch);
                assertThat(count(connection, "select remaining_message_budget from public.app_realtime_control"))
                        .isEqualTo(100);
                assertThat(count(connection, "select count(*) from public.realtime_send_accounting")).isZero();
                assertThat(count(connection, "select count(*) from public.realtime_test_capture")).isZero();
                assertThat(count(connection, "select count(*) from public.realtime_signal_outbox where sent_at is null"))
                        .isEqualTo(2);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Order(9)
    void missingRealtimeMessagesFailsWithAClearPrerequisiteError() throws Exception {
        try (Connection connection = connect()) {
            execute(connection, "drop schema realtime cascade");
            assertThatThrownBy(() -> execute(connection, script))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Supabase Realtime schema not found");
        }
    }

    private static void assertRestrictedFunctions(Connection connection) throws SQLException {
        assertThat(count(connection, """
                select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'app_private'
                  and p.proname in ('realtime_inbox_allowed', 'send_realtime_signal', 'dispatch_realtime_signals')
                  and p.prosecdef and 'search_path=""' = any(p.proconfig)
                """)).isEqualTo(3);
        assertThat(count(connection, """
                select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                where n.nspname = 'app_private'
                  and p.proname in ('realtime_inbox_allowed', 'send_realtime_signal', 'dispatch_realtime_signals')
                  and a.privilege_type = 'EXECUTE' and a.grantee <> p.proowner
                  and not (p.proname = 'realtime_inbox_allowed' and a.grantee = 'authenticated'::regrole
                           and not a.is_grantable)
                """)).as("No PUBLIC, default, or inherited-parent execution grant survives").isZero();
        assertThat(count(connection, """
                select count(*) from pg_roles r cross join pg_proc p
                join pg_namespace n on n.oid = p.pronamespace
                where r.rolname in ('anon', 'authenticated') and n.nspname = 'app_private'
                  and p.proname in ('realtime_inbox_allowed', 'send_realtime_signal', 'dispatch_realtime_signals')
                  and has_function_privilege(r.oid, p.oid, 'EXECUTE')
                  and not (r.rolname = 'authenticated' and p.proname = 'realtime_inbox_allowed')
                """)).as("Effective privileges include inherited membership and PUBLIC grants").isZero();
    }

    private static long visibleRows(String role, String topic, String claims, String extension) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                clientContext(connection, role, topic, claims);
                try (PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from realtime.messages where extension = ?")) {
                    statement.setString(1, extension);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        return rows.getLong(1);
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void clientContext(Connection connection, String role, String topic, String claims)
            throws SQLException {
        assertThat(role).isIn("anon", "authenticated");
        try (PreparedStatement statement = connection.prepareStatement(
                "select set_config('request.jwt.claims', ?, true), set_config('realtime.topic', ?, true)")) {
            statement.setString(1, claims);
            statement.setString(2, topic);
            statement.execute();
        }
        execute(connection, "set local role " + role);
    }

    private static void dispatchFixture(Connection connection) throws SQLException {
        execute(connection, """
                update public.app_realtime_control set enabled = true, remaining_message_budget = 100;
                insert into public.realtime_token_leases
                    (jti, admitted_product_ids, inbox_allowed, expires_at, retire_at)
                values ('00000000-0000-0000-0000-000000000014', '["p1", "p2"]', false,
                        '2026-11-04T00:05:00Z', '2026-11-04T00:05:30Z');
                insert into public.realtime_signal_outbox (topic, event_name, payload, created_at)
                values ('stock:p1', 'stock.changed', '{"productId":"p1"}', '2026-11-04T00:00:00Z');
                """);
    }

    private static void sendSignal(Connection connection, String topic, String event, String payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select app_private.send_realtime_signal(?, ?, ?::jsonb)")) {
            statement.setString(1, topic);
            statement.setString(2, event);
            statement.setString(3, payload);
            statement.execute();
        }
    }

    private static String policyCommand(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select cmd from pg_policies
                 where schemaname = 'realtime' and tablename = 'messages' and policyname = ?
                """)) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static boolean inboxAllowed(Connection connection, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select app_private.realtime_inbox_allowed(?)")) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static long insertUser(String status) throws SQLException {
        String suffix = UUID.randomUUID().toString();
        try (Connection connection = connect()) {
            long roleId;
            try (PreparedStatement role = connection.prepareStatement("""
                    insert into roles (role_id, role_code, role_name)
                    values (?, ?, 'Smoke role') returning id
                    """)) {
                role.setString(1, "role_" + suffix);
                role.setString(2, "smoke_" + suffix);
                try (ResultSet rows = role.executeQuery()) {
                    rows.next();
                    roleId = rows.getLong(1);
                }
            }
            try (PreparedStatement user = connection.prepareStatement("""
                    insert into users (user_id, user_email, user_password_hash, role_id, user_status)
                    values (?, ?, 'scratch-only', ?, ?) returning id
                    """)) {
                user.setString(1, "USR_" + suffix);
                user.setString(2, suffix + "@example.invalid");
                user.setLong(3, roleId);
                user.setString(4, status);
                try (ResultSet rows = user.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
