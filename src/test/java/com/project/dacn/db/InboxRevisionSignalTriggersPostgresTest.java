package com.project.dacn.db;

import com.project.dacn.support.ExternalPostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL proof for the operator-stage inbox revision hint trigger.
 *
 * <p>The whole class runs in an invocation-owned {@code verify_event_foundation_<12 hex>}
 * scratch database. It installs the script twice, exercises revision changes, and
 * proves the trigger only calls foundation's best-effort enqueue, never sends,
 * locks the shared control row, or writes lease/accounting data.</p>
 */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InboxRevisionSignalTriggersPostgresTest {

    private static final String TRIGGER_NAME = "users_inbox_revision_signal";
    private static final String FUNCTION_NAME = "public.enqueue_inbox_revision_signal()";

    private static ExternalPostgresDatabase database;
    private static String scratchName;
    private static String script;

    @BeforeAll
    static void prepareOwnedScratchDatabase() throws Exception {
        database = ExternalPostgresDatabase.create();
        scratchName = URI.create(database.jdbcUrl().substring("jdbc:".length()))
                .getPath().substring(1);
        assertThat(scratchName).startsWith("verify_event_foundation_");
        assertThat(scratchName).matches("verify_event_foundation_[0-9a-f]{12}");

        try (Connection connection = connect()) {
            assertThat(currentDatabase(connection)).isEqualTo(scratchName);
            System.out.println("InboxRevisionSignalTriggers scratch identity: " + scratchName);
        }

        database.migrate();
        script = Files.readString(Path.of(
                "src/main/resources/db/supabase/order_notifications_realtime.sql"));

        try (Connection connection = connect()) {
            assertThat(currentDatabase(connection)).isEqualTo(scratchName);
            assertThat(count(connection, """
                    select count(*) from flyway_schema_history
                     where version in ('34', '35', '36', '37') and success
                    """)).isEqualTo(4);
            assertThat(count(connection, """
                    select count(*) from flyway_schema_history
                     where version not in ('1','2','3','4','5','6','7','8','9','10','11','12','13',
                                           '14','15','16','17','18','19','20','21','22','23','24',
                                           '25','26','27','28','29','30','31','32','33','34','35','36','37','38')
                       and success
                    """)).as("V38 remains the highest migration").isZero();

            execute(connection, script);
            execute(connection, script);

            System.out.println("InboxRevisionSignalTriggers PostgreSQL evidence: Flyway V34/V35/V36/V37 successful, "
                    + "operator triggers=" + operatorTriggerCount(connection));
        }
    }

    @AfterAll
    static void dropOnlyOwnedScratchDatabase() throws Exception {
        if (database == null) {
            return;
        }
        database.drop();
        try (Connection connection = DriverManager.getConnection(
                System.getenv("TEST_POSTGRES_URL"), database.username(), database.password());
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from pg_database where datname = ?")) {
            statement.setString(1, scratchName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1))
                        .as("Only this invocation's scratch database is removed")
                        .isZero();
            }
        }
        System.out.println("InboxRevisionSignalTriggers scratch cleanup: " + scratchName + "; remaining=0");
    }

    @BeforeEach
    void resetFixturesAndControl() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(currentDatabase(connection)).isEqualTo(scratchName);
            execute(connection, """
                    truncate table public.realtime_signal_outbox,
                                   public.realtime_send_accounting,
                                   public.realtime_token_leases,
                                   public.notifications,
                                   public.users
                    restart identity cascade
                    """);
            execute(connection, """
                    update public.app_realtime_control
                       set enabled = true,
                           remaining_message_budget = 1000,
                           updated_at = clock_timestamp()
                     where id = 1
                    """);
        }
    }

    @Test
    @Order(1)
    void scriptReappliesWithoutDuplicatesAndLeavesV37Untouched() throws SQLException {
        try (Connection connection = connect()) {
            assertOperatorInstallShape(connection);

            String functionDefinition = string(connection,
                    "select pg_get_functiondef('" + FUNCTION_NAME + "'::regprocedure)");
            long functionOid = count(connection,
                    "select oid from pg_proc where oid = '" + FUNCTION_NAME + "'::regprocedure");
            long relationCount = count(connection, """
                    select count(*) from pg_class c
                      join pg_namespace n on n.oid = c.relnamespace
                     where n.nspname = 'public'
                    """);

            execute(connection, script);
            execute(connection, script);

            assertOperatorInstallShape(connection);
            assertThat(count(connection, """
                    select count(*) from pg_class c
                      join pg_namespace n on n.oid = c.relnamespace
                     where n.nspname = 'public'
                    """)).as("Reapply creates no new relations").isEqualTo(relationCount);
            assertThat(string(connection,
                    "select pg_get_functiondef('" + FUNCTION_NAME + "'::regprocedure)"))
                    .isEqualTo(functionDefinition);
            assertThat(count(connection,
                    "select oid from pg_proc where oid = '" + FUNCTION_NAME + "'::regprocedure"))
                    .isEqualTo(functionOid);
        }
    }

    @Test
    @Order(2)
    void changedRevisionEnqueuesOneExactMinimalInboxHint() throws SQLException {
        try (Connection connection = connect()) {
            long user = user(connection, "task7-hint-user");
            incrementRevision(connection, user);

            assertThat(pendingHints(connection)).isEqualTo(1);
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where topic = ?
                       and event_name = 'inbox.changed'
                       and payload = jsonb_build_object('revision', 1)
                       and payload - 'revision' = '{}'::jsonb
                       and (select count(*) from jsonb_object_keys(payload)) = 1
                       and octet_length(payload::text) <= 1024
                       and sent_at is null
                       and reserved_message_cost is null
                    """, "inbox:" + user)).isEqualTo(1);
            assertThat(revision(connection, user)).isEqualTo(1);
        }
    }

    @Test
    @Order(3)
    void insertAndNoOpRevisionUpdatesEnqueueNothing() throws SQLException {
        try (Connection connection = connect()) {
            long user = user(connection, "task7-noop-user");
            assertThat(revision(connection, user)).isZero();
            assertThat(pendingHints(connection)).isZero();

            // Setting the same value is not a revision change and must stay silent.
            execute(connection, "update public.users set inbox_revision = 0 where id = ?", user);
            assertThat(pendingHints(connection)).isZero();

            // A non-revision column update must not fire the UPDATE OF trigger.
            execute(connection, "update public.users set user_last_login = clock_timestamp() where id = ?",
                    user);
            assertThat(pendingHints(connection)).isZero();

            incrementRevision(connection, user);
            assertThat(pendingHints(connection)).isEqualTo(1);
        }
    }

    @Test
    @Order(4)
    void fanoutReadDeleteSequenceQueuesOnlyChangedRevisions() throws SQLException {
        try (Connection connection = connect()) {
            long user = user(connection, "task7-sequence-user");
            execute(connection, "update public.users set inbox_revision = 10 where id = ?", user);
            truncateOutbox(connection);

            incrementRevision(connection, user); // fan-out -> 11
            incrementRevision(connection, user); // read -> 12
            incrementRevision(connection, user); // delete -> 13

            assertThat(pendingHints(connection)).isEqualTo(3);
            assertThat(revisions(connection, user)).containsExactly(11L, 12L, 13L);
            assertThat(revision(connection, user)).isEqualTo(13);
        }
    }

    @Test
    @Order(5)
    void rollbackRemovesTheHintAndTheRevisionIncrement() throws SQLException {
        try (Connection connection = connect()) {
            long user = user(connection, "task7-rollback-user");
            connection.setAutoCommit(false);
            try {
                incrementRevision(connection, user);
                assertThat(pendingHints(connection)).isEqualTo(1);
                assertThat(revision(connection, user)).isEqualTo(1);
            } finally {
                connection.rollback();
            }
            assertThat(pendingHints(connection)).isZero();
            assertThat(revision(connection, user)).isZero();
        }
    }

    @Test
    @Order(6)
    void disabledOrZeroBudgetCommitsTheRevisionWithoutHintsOrAccounting() throws SQLException {
        boolean[] enabledValues = {false, true};
        long[] budgets = {1000, 0};
        try (Connection connection = connect()) {
            for (int index = 0; index < enabledValues.length; index++) {
                long user = user(connection, "task7-budget-user-" + index);
                execute(connection, "update public.app_realtime_control "
                                + "set enabled = ?, remaining_message_budget = ? where id = 1",
                        enabledValues[index], budgets[index]);

                incrementRevision(connection, user);

                assertThat(revision(connection, user)).isEqualTo(1);
                assertThat(pendingHints(connection)).isZero();
                assertThat(count(connection,
                        "select count(*) from public.realtime_send_accounting")).isZero();
                assertThat(count(connection,
                        "select count(*) from public.realtime_token_leases")).isZero();
            }
        }
    }

    @Test
    @Order(7)
    void enqueueFailureIsSwallowedAndTheRevisionStillAdvances() throws SQLException {
        try (Connection connection = connect()) {
            long user = user(connection, "task7-enqueue-failure-user");
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        alter function public.app_realtime_enqueue_signal(text, text, jsonb)
                            rename to app_realtime_enqueue_signal_task7_off
                        """);
                incrementRevision(connection, user);
                assertThat(revision(connection, user)).isEqualTo(1);
                assertThat(pendingHints(connection)).isZero();
            } finally {
                connection.rollback();
            }
            assertThat(count(connection, """
                    select count(*) from pg_proc
                     where oid = 'public.app_realtime_enqueue_signal(text, text, jsonb)'::regprocedure
                    """)).isEqualTo(1);
            assertThat(revision(connection, user)).isZero();
        }
    }

    @Test
    @Order(8)
    void triggerPathDoesNotLockControlCallRealtimeSendOrWriteLeasesAndAccounting() throws Exception {
        try (Connection main = connect(); Connection locker = connect()) {
            long user = user(main, "task7-isolation-user");

            String controlBefore = string(main, """
                    select enabled || ':' || remaining_message_budget || ':' || updated_at
                      from public.app_realtime_control where id = 1
                    """);
            locker.setAutoCommit(false);
            execute(locker, "select id from public.app_realtime_control where id = 1 for update");

            main.setAutoCommit(false);
            try {
                execute(main, "set local lock_timeout = '500ms'");
                execute(main, """
                        create schema if not exists realtime;
                        create table if not exists public.realtime_test_capture (
                            topic text, event text, payload jsonb
                        );
                        create or replace function realtime.send(
                                payload jsonb, event text, topic text, private boolean)
                        returns void language sql as $$
                            insert into public.realtime_test_capture (topic, event, payload)
                            values (topic, event, payload)
                        $$;
                        """);

                incrementRevision(main, user);

                assertThat(revision(main, user)).isEqualTo(1);
                assertThat(pendingHints(main)).isEqualTo(1);
                assertThat(count(main,
                        "select count(*) from public.realtime_test_capture")).isZero();
                assertThat(count(main,
                        "select count(*) from public.realtime_send_accounting")).isZero();
                assertThat(count(main,
                        "select count(*) from public.realtime_token_leases")).isZero();
                assertThat(string(main, """
                        select enabled || ':' || remaining_message_budget || ':' || updated_at
                          from public.app_realtime_control where id = 1
                        """)).as("No control lock or budget update from the trigger path")
                        .isEqualTo(controlBefore);
            } finally {
                main.rollback();
                locker.rollback();
            }

            assertThat(pendingHints(main)).isZero();
            assertThat(revision(main, user)).isZero();
        }
    }

    @Test
    @Order(9)
    void queuedHintCarriesNoMessageCountEmailOrOrderData() throws SQLException {
        try (Connection connection = connect()) {
            long user = user(connection, "task7-payload-user");
            incrementRevision(connection, user);
            incrementRevision(connection, user);

            assertThat(pendingHints(connection)).isEqualTo(2);
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where (select count(*) from jsonb_object_keys(payload)) <> 1
                        or not jsonb_exists(payload, 'revision')
                        or payload - 'revision' <> '{}'::jsonb
                        or topic <> 'inbox:' || ?
                        or event_name <> 'inbox.changed'
                        or octet_length(payload::text) > 1024
                    """, user)).isZero();
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where payload::text ~* '(message|unread|count|email|order|customer|user|status)'
                    """)).as("Payload reveals no message, count, email, order or user data")
                    .isZero();
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox h
                     where to_jsonb(h)::text ~* '(customer|email|unread|order|notification)'
                    """)).as("The complete outbox row carries no business data")
                    .isZero();
        }
    }

    @Test
    @Order(10)
    void missingPrerequisitesFailClosedWithClearErrors() throws Exception {
        String[][] cases = {
                {"alter table public.realtime_signal_outbox rename to task7_missing_outbox",
                        "public.realtime_signal_outbox missing"},
                {"delete from public.app_realtime_control where id = 1",
                        "app_realtime_control row id=1 missing"},
                {"alter function public.app_realtime_enqueue_signal(text, text, jsonb) "
                        + "rename to task7_missing_enqueue",
                        "app_realtime_enqueue_signal(text,text,jsonb) missing"},
                {"alter table public.users rename to task7_missing_users",
                        "public.users missing"},
                {"alter table public.users rename column id to task7_missing_id",
                        "public.users.id missing"},
                {"alter table public.users rename column inbox_revision to task7_missing_revision",
                        "public.users.inbox_revision missing"}
        };

        for (String[] missing : cases) {
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    execute(connection, missing[0]);
                    assertThatThrownBy(() -> execute(connection, script))
                            .as(missing[1])
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("Inbox realtime prerequisite not found")
                            .hasMessageContaining(missing[1]);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private static void assertOperatorInstallShape(Connection connection) throws SQLException {
        assertThat(count(connection, """
                select count(*) from pg_proc p
                  join pg_namespace n on n.oid = p.pronamespace
                 where n.nspname = 'public'
                   and p.proname = 'enqueue_inbox_revision_signal'
                   and p.pronargs = 0
                   and p.prosecdef
                   and 'search_path=""' = any(p.proconfig)
                """)).as("One shared security-definer trigger function").isEqualTo(1);
        assertThat(count(connection, """
                select count(*) from pg_proc p
                  cross join lateral aclexplode(
                          coalesce(p.proacl, acldefault('f', p.proowner))) a
                 where p.oid = 'public.enqueue_inbox_revision_signal()'::regprocedure
                   and a.privilege_type = 'EXECUTE'
                   and a.grantee <> p.proowner
                """)).as("All direct EXECUTE grants are revoked").isZero();
        assertThat(count(connection, """
                select count(*) from pg_proc p
                  cross join lateral aclexplode(
                          coalesce(p.proacl, acldefault('f', p.proowner))) a
                 where p.oid = 'public.enqueue_inbox_revision_signal()'::regprocedure
                   and a.grantee = 0
                """)).as("PUBLIC cannot execute the trigger function").isZero();
        assertThat(count(connection, """
                select count(*) from pg_trigger
                 where tgname = 'users_inbox_revision_signal'
                   and not tgisinternal
                   and tgrelid = 'public.users'::regclass
                """)).as("One AFTER UPDATE OF inbox_revision trigger").isEqualTo(1);
        assertThat(operatorTriggerCount(connection)).isEqualTo(1);
    }

    private static long operatorTriggerCount(Connection connection) throws SQLException {
        return count(connection, """
                select count(*) from pg_trigger
                 where tgname = 'users_inbox_revision_signal' and not tgisinternal
                """);
    }

    private static long user(Connection connection, String businessId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into public.users (user_id, user_email, user_password_hash, user_status, role_id)
                select ?, ?, 'unused', 'ACTIVE', id from roles where role_code = 'customer'
                returning id
                """)) {
            statement.setString(1, businessId);
            statement.setString(2, businessId + "@example.invalid");
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static void incrementRevision(Connection connection, long user) throws SQLException {
        execute(connection,
                "update public.users set inbox_revision = inbox_revision + 1 where id = ?", user);
    }

    private static void truncateOutbox(Connection connection) throws SQLException {
        execute(connection, "truncate table public.realtime_signal_outbox restart identity");
    }

    private static long revision(Connection connection, long user) throws SQLException {
        return count(connection, "select inbox_revision from public.users where id = ?", user);
    }

    private static java.util.List<Long> revisions(Connection connection, long user) throws SQLException {
        java.util.List<Long> values = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select (payload ->> 'revision')::bigint
                  from public.realtime_signal_outbox
                 where topic = ? and event_name = 'inbox.changed' and sent_at is null
                 order by id
                """)) {
            statement.setString(1, "inbox:" + user);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getLong(1));
                }
            }
        }
        return values;
    }

    private static long pendingHints(Connection connection) throws SQLException {
        return count(connection,
                "select count(*) from public.realtime_signal_outbox where sent_at is null");
    }

    private static String currentDatabase(Connection connection) throws SQLException {
        return string(connection, "select current_database()");
    }

    private static String string(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static long count(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        if (parameters.length == 0) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.execute();
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters)
            throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
    }
}
