package com.project.dacn.db;

import com.project.dacn.support.ExternalPostgresDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockedStatic;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class EventFoundationMigrationTest {

    private static ExternalPostgresDatabase database;
    private static String scratchDatabaseName;

    @BeforeAll
    static void migrateScratchDatabase() throws SQLException {
        database = ExternalPostgresDatabase.create();
        scratchDatabaseName = URI.create(database.jdbcUrl().substring("jdbc:".length()))
                .getPath().substring(1);
        assertThat(scratchDatabaseName).startsWith("verify_event_foundation_");
        assertThat(scalar("select current_database()"))
                .as("the connection must target this invocation's generated scratch database")
                .isEqualTo(scratchDatabaseName);
        System.out.println("Verified scratch database identity: " + scratchDatabaseName);
        System.out.println("Cluster browser roles present: " + strings(
                "select rolname from pg_roles where rolname in ('anon', 'authenticated') "
                        + "order by rolname"));
        database.migrate();
    }

    @AfterAll
    static void dropScratchDatabase() throws SQLException {
        if (database != null) {
            database.drop();
            try (Connection connection = DriverManager.getConnection(
                    System.getenv("TEST_POSTGRES_URL"),
                    System.getenv("TEST_POSTGRES_USERNAME"),
                    System.getenv("TEST_POSTGRES_PASSWORD"));
                 PreparedStatement statement = connection.prepareStatement(
                         "select 1 from pg_database where datname = ?")) {
                statement.setString(1, scratchDatabaseName);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("only this invocation's scratch database is gone")
                            .isFalse();
                }
            }
            System.out.println("Confirmed scratch database cleanup: " + scratchDatabaseName);
        }
    }

    @Test
    void refusesMigrationWhenTheConnectionReportsAnotherDatabase() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("select current_database()")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("unexpected_database");

        try (MockedStatic<DriverManager> drivers = mockStatic(DriverManager.class);
             MockedStatic<Flyway> flyway = mockStatic(Flyway.class)) {
            drivers.when(() -> DriverManager.getConnection(
                    database.jdbcUrl(), database.username(), database.password()))
                    .thenReturn(connection);

            assertThat(catchThrowable(database::migrate))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Refusing to migrate unexpected PostgreSQL database");
            flyway.verifyNoInteractions();
        }
    }

    @Test
    void browserRoleRevokesIterateOnlyExistingAllowlistedRolesWithQuotedIdentifiers()
            throws IOException {
        String migration = new ClassPathResource("db/migration/V30__event_foundation.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration).containsPattern("(?is)for\\s+v_role\\s+in\\s+"
                + "select\\s+rolname\\s+from\\s+pg_roles\\s+where\\s+rolname\\s+in\\s*"
                + "\\(\\s*'anon'\\s*,\\s*'authenticated'\\s*\\)\\s+loop");
        assertThat(migration).containsPattern("(?is)execute\\s+format\\(\\s*"
                + "'revoke all on table\\s+public\\.event_outbox,.*?"
                + "public\\.app_realtime_control,.*?public\\.realtime_token_leases,.*?"
                + "public\\.realtime_signal_outbox,.*?public\\.realtime_send_accounting\\s+"
                + "from %I'\\s*,\\s*v_role\\s*\\)");
        assertThat(migration).containsPattern("(?is)execute\\s+format\\(\\s*"
                + "'revoke execute on function public\\.app_realtime_enqueue_signal"
                + "\\(text, text, jsonb\\)\\s+from %I'\\s*,\\s*v_role\\s*\\)");
    }

    @Test
    void ordersHaveNonNegativeEventSequence() throws SQLException {
        ColumnInfo column = column("orders", "event_sequence");

        assertThat(column.dataType()).isEqualTo("bigint");
        assertThat(column.nullable()).isEqualTo("NO");
        assertThat(column.defaultValue()).isEqualTo("0");
        assertThat(constraintDefinitions("orders", "c"))
                .anySatisfy(definition -> assertThat(definition).contains("event_sequence >= 0"));
    }

    @Test
    void eventOutboxHasUniquenessLeaseAndTerminalColumns() throws SQLException {
        assertThat(column("event_outbox", "event_id").dataType()).isEqualTo("uuid");
        assertThat(column("event_outbox", "event_id").nullable()).isEqualTo("NO");
        assertThat(column("event_outbox", "order_id").dataType()).isEqualTo("bigint");
        assertThat(column("event_outbox", "order_sequence").dataType()).isEqualTo("bigint");
        assertThat(column("event_outbox", "payload").dataType()).isEqualTo("jsonb");
        assertThat(column("event_outbox", "payload").nullable()).isEqualTo("NO");
        assertThat(column("event_outbox", "lease_owner").maxLength()).isEqualTo(100);
        assertThat(column("event_outbox", "lease_token").dataType()).isEqualTo("uuid");
        assertThat(column("event_outbox", "lease_expires_at").dataType())
                .isEqualTo("timestamp with time zone");
        assertThat(column("event_outbox", "attempt_count").dataType()).isEqualTo("integer");
        assertThat(column("event_outbox", "attempt_count").defaultValue()).isEqualTo("0");
        assertThat(column("event_outbox", "published_at").dataType())
                .isEqualTo("timestamp with time zone");
        assertThat(column("event_outbox", "quarantined_at").dataType())
                .isEqualTo("timestamp with time zone");
        assertThat(column("event_outbox", "last_error_code").maxLength()).isEqualTo(64);

        List<String> uniqueConstraints = constraintDefinitions("event_outbox", "u");
        assertThat(uniqueConstraints)
                .anySatisfy(definition -> assertThat(definition).contains("UNIQUE (event_id)"));
        assertThat(uniqueConstraints)
                .anySatisfy(definition -> assertThat(definition)
                        .contains("UNIQUE (order_id, order_sequence)"));
        assertThat(constraintDefinitions("event_outbox", "f"))
                .anySatisfy(definition -> assertThat(definition).contains(
                        "FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT"));

        List<String> checks = constraintDefinitions("event_outbox", "c");
        assertThat(checks).anySatisfy(definition ->
                assertThat(definition).contains("order_sequence > 0"));
        assertThat(checks).anySatisfy(definition ->
                assertThat(definition).contains("schema_version > 0"));
        assertThat(checks).anySatisfy(definition ->
                assertThat(definition).contains("attempt_count >= 0"));
        assertThat(checks).anySatisfy(definition -> assertThat(definition)
                .contains("NOT")
                .contains("published_at IS NOT NULL")
                .contains("quarantined_at IS NOT NULL"));
    }

    @Test
    void eventOutboxDueIndexIsPartial() throws SQLException {
        assertThat(indexDefinition("idx_event_outbox_due"))
                .contains("ON public.event_outbox USING btree (available_at, id)")
                .contains("published_at IS NULL")
                .contains("quarantined_at IS NULL");
    }

    @Test
    void realtimeControlHasExactlyTheDisabledZeroBudgetRow() throws SQLException {
        assertThat(count("select count(*) from public.app_realtime_control")).isEqualTo(1);
        assertThat(((Number) scalar("select id from public.app_realtime_control where id = 1"))
                .intValue()).isEqualTo(1);
        assertThat(scalar("select enabled from public.app_realtime_control where id = 1"))
                .isEqualTo(false);
        assertThat(scalar("select remaining_message_budget "
                + "from public.app_realtime_control where id = 1")).isEqualTo(0L);
    }

    @Test
    void realtimeTokenLeasesHaveChecksAndIndexes() throws SQLException {
        assertThat(column("realtime_token_leases", "jti").dataType()).isEqualTo("uuid");
        assertThat(column("realtime_token_leases", "user_id").dataType()).isEqualTo("bigint");
        assertThat(column("realtime_token_leases", "admitted_product_ids").dataType())
                .isEqualTo("jsonb");
        assertThat(column("realtime_token_leases", "admitted_product_ids").nullable())
                .isEqualTo("NO");
        assertThat(column("realtime_token_leases", "inbox_allowed").dataType())
                .isEqualTo("boolean");
        assertThat(column("realtime_token_leases", "expires_at").dataType())
                .isEqualTo("timestamp with time zone");
        assertThat(column("realtime_token_leases", "retire_at").dataType())
                .isEqualTo("timestamp with time zone");

        List<String> checks = constraintDefinitions("realtime_token_leases", "c");
        assertThat(checks).anySatisfy(definition -> assertThat(definition)
                .contains("jsonb_typeof(admitted_product_ids) = 'array'"));
        assertThat(checks).anySatisfy(definition -> assertThat(definition)
                .contains("retire_at >= expires_at"));
        assertThat(constraintDefinitions("realtime_token_leases", "f"))
                .anySatisfy(definition -> assertThat(definition).contains(
                        "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"));

        assertThat(indexDefinition("idx_realtime_token_leases_retire_at"))
                .contains("USING btree (retire_at)");
        assertThat(indexDefinition("idx_realtime_token_leases_products"))
                .contains("USING gin (admitted_product_ids jsonb_path_ops)");
    }

    @Test
    void realtimeSignalAndAccountingTablesHaveChecksAndIndexes() throws SQLException {
        assertThat(column("realtime_signal_outbox", "topic").maxLength()).isEqualTo(300);
        assertThat(column("realtime_signal_outbox", "event_name").maxLength()).isEqualTo(32);
        assertThat(column("realtime_signal_outbox", "payload").dataType()).isEqualTo("jsonb");
        assertThat(column("realtime_signal_outbox", "sent_at").nullable()).isEqualTo("YES");
        assertThat(column("realtime_signal_outbox", "reserved_message_cost").nullable())
                .isEqualTo("YES");
        assertThat(column("realtime_send_accounting", "reserved_message_cost").nullable())
                .isEqualTo("NO");
        assertThat(column("realtime_send_accounting", "sent_at").nullable()).isEqualTo("NO");

        assertThat(constraintDefinitions("realtime_signal_outbox", "c"))
                .anySatisfy(definition -> assertThat(definition)
                        .contains("reserved_message_cost >= 0"));
        assertThat(constraintDefinitions("realtime_send_accounting", "c"))
                .anySatisfy(definition -> assertThat(definition)
                        .contains("reserved_message_cost >= 0"));

        assertThat(indexDefinition("idx_realtime_signal_outbox_pending"))
                .contains("USING btree (id)")
                .contains("sent_at IS NULL");
        assertThat(indexDefinition("idx_realtime_signal_outbox_recent"))
                .contains("USING btree (sent_at)")
                .contains("sent_at IS NOT NULL");
        assertThat(indexDefinition("idx_realtime_send_accounting_sent_at"))
                .contains("USING btree (sent_at)");
    }

    @Test
    void enqueueFunctionExistsWithoutPublicExecute() throws SQLException {
        assertThat(count("select count(*) from pg_proc p "
                + "where p.pronamespace = 'public'::regnamespace "
                + "and p.proname = 'app_realtime_enqueue_signal' and p.pronargs = 3"))
                .isEqualTo(1);

        List<Long> publicGrantees = numbers("select acl.grantee from pg_proc p "
                + "cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl "
                + "where p.pronamespace = 'public'::regnamespace "
                + "and p.proname = 'app_realtime_enqueue_signal' and p.pronargs = 3 "
                + "and acl.grantee = 0");
        assertThat(publicGrantees).isEmpty();

        assertThat(scalar("select p.prosecdef from pg_proc p "
                + "where p.pronamespace = 'public'::regnamespace "
                + "and p.proname = 'app_realtime_enqueue_signal' and p.pronargs = 3"))
                .as("enqueue function must be security definer")
                .isEqualTo(true);

        assertThat(strings("select unnest(coalesce(p.proconfig, '{}')) from pg_proc p "
                + "where p.pronamespace = 'public'::regnamespace "
                + "and p.proname = 'app_realtime_enqueue_signal' and p.pronargs = 3"))
                .as("enqueue function must pin an empty search_path")
                .contains("search_path=\"\"");

        // Roles that do not exist on this server are skipped by the join, not asserted against.
        List<String> browserGrantees = strings("select r.rolname || ':' || acl.privilege_type "
                + "from pg_proc p "
                + "cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl "
                + "join pg_roles r on r.oid = acl.grantee "
                + "where p.pronamespace = 'public'::regnamespace "
                + "and p.proname = 'app_realtime_enqueue_signal' and p.pronargs = 3 "
                + "and r.rolname in ('anon', 'authenticated')");
        assertThat(browserGrantees)
                .as("browser roles must not execute the enqueue function")
                .isEmpty();
    }

    @Test
    void realtimeOutboxTablesAreNotAccessibleToPublic() throws SQLException {
        List<String> publicPrivileges = strings("select c.relname || ':' || acl.privilege_type "
                + "from pg_class c "
                + "cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) acl "
                + "where c.relnamespace = 'public'::regnamespace "
                + "and c.relname in ('realtime_signal_outbox', 'realtime_send_accounting') "
                + "and acl.grantee = 0");
        assertThat(publicPrivileges).isEmpty();
    }

    @Test
    void realtimeTablesAreNotAccessibleToBrowserRoles() throws SQLException {
        // Roles that do not exist on this server are skipped by the join, not asserted against.
        List<String> browserPrivileges = strings("select c.relname || ':' || r.rolname || ':' "
                + "|| acl.privilege_type from pg_class c "
                + "cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) acl "
                + "join pg_roles r on r.oid = acl.grantee "
                + "where c.relnamespace = 'public'::regnamespace "
                + "and c.relname in ('event_outbox', 'app_realtime_control', "
                + "'realtime_token_leases', 'realtime_signal_outbox', 'realtime_send_accounting') "
                + "and r.rolname in ('anon', 'authenticated')");
        assertThat(browserPrivileges)
                .as("browser roles must have no privileges on realtime tables")
                .isEmpty();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
    }

    private static ColumnInfo column(String table, String columnName) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "select data_type, is_nullable, column_default, character_maximum_length "
                             + "from information_schema.columns "
                             + "where table_schema = 'public' and table_name = ? "
                             + "and column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next())
                        .as("column %s.%s should exist", table, columnName)
                        .isTrue();
                return new ColumnInfo(
                        resultSet.getString("data_type"),
                        resultSet.getString("is_nullable"),
                        resultSet.getString("column_default"),
                        resultSet.getObject("character_maximum_length", Integer.class));
            }
        }
    }

    private static List<String> constraintDefinitions(String table, String type)
            throws SQLException {
        return strings("select pg_get_constraintdef(oid) from pg_constraint "
                + "where conrelid = ?::regclass and contype = ?", "public." + table, type);
    }

    private static String indexDefinition(String indexName) throws SQLException {
        List<String> definitions = strings(
                "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
                indexName);
        assertThat(definitions).as("index %s should exist", indexName).hasSize(1);
        return definitions.get(0);
    }

    private static long count(String sql, Object... parameters) throws SQLException {
        Object value = scalar(sql, parameters);
        return ((Number) value).longValue();
    }

    private static Object scalar(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("query should return a row").isTrue();
                return resultSet.getObject(1);
            }
        }
    }

    private static List<String> strings(String sql, Object... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
            }
        }
        return values;
    }

    private static List<Long> numbers(String sql, Object... parameters) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(resultSet.getLong(1));
                }
            }
        }
        return values;
    }

    private record ColumnInfo(String dataType, String nullable, String defaultValue,
                              Integer maxLength) {
    }
}
