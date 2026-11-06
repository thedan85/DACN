package com.project.dacn.db;

import com.project.dacn.support.ExternalPostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class RealtimeEnqueueFunctionPostgresTest {

    private static ExternalPostgresDatabase database;

    @BeforeAll
    static void migrateScratchDatabase() {
        database = ExternalPostgresDatabase.create();
        database.migrate();
    }

    @AfterAll
    static void dropScratchDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @BeforeEach
    void resetOutboxAndControl() throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("truncate table public.realtime_signal_outbox restart identity");
            statement.execute("update public.app_realtime_control "
                    + "set enabled = false, remaining_message_budget = 0 where id = 1");
        }
    }

    @Test
    void enabledControlWithBudgetInsertsHint() throws SQLException {
        setControl(true, 5);

        try (Connection connection = connect()) {
            call(connection, "stock:42", "INSERT", "{\"price\":10}");
        }

        assertThat(count("select count(*) from public.realtime_signal_outbox")).isEqualTo(1);
        assertThat(count("select count(*) from public.realtime_signal_outbox "
                + "where topic = 'stock:42' and event_name = 'INSERT' "
                + "and payload = '{\"price\":10}'::jsonb")).isEqualTo(1);
    }

    @Test
    void disabledControlInsertsNothing() throws SQLException {
        setControl(false, 5);

        try (Connection connection = connect()) {
            call(connection, "stock:42", "INSERT", "{}");
        }

        assertThat(count("select count(*) from public.realtime_signal_outbox")).isZero();
    }

    @Test
    void zeroBudgetInsertsNothing() throws SQLException {
        setControl(true, 0);

        try (Connection connection = connect()) {
            call(connection, "stock:42", "INSERT", "{}");
        }

        assertThat(count("select count(*) from public.realtime_signal_outbox")).isZero();
    }

    @Test
    void invalidInputsProduceNoRowAndNoException() throws SQLException {
        setControl(true, 100);

        try (Connection connection = connect()) {
            assertDoesNotThrow(() -> call(connection, "catalog:1", "INSERT", "{}"));
            assertDoesNotThrow(() -> call(connection, "stock:" + "a".repeat(300), "INSERT", "{}"));
            assertDoesNotThrow(() -> call(connection, "stock:1", "E".repeat(33), "{}"));
            assertDoesNotThrow(() -> call(connection, "stock:1", "INSERT", null));
            assertDoesNotThrow(() -> call(connection, "stock:1", "INSERT",
                    "{\"text\":\"" + "a".repeat(1024) + "\"}"));
        }

        assertThat(count("select count(*) from public.realtime_signal_outbox")).isZero();
    }

    @Test
    void outerTransactionStillCommitsAfterInvalidHint() throws SQLException {
        setControl(true, 10);

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            call(connection, "catalog:1", "INSERT", "{}");
            call(connection, "stock:7", "INSERT", "{\"price\":1}");
            connection.commit();
        }

        assertThat(count("select count(*) from public.realtime_signal_outbox "
                + "where topic = 'stock:7'")).isEqualTo(1);
    }

    @Test
    void unexpectedFailureIsIsolatedInFunctionSubtransaction() throws SQLException {
        setControl(true, 10);

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("savepoint s");
                statement.execute("drop table public.realtime_signal_outbox");
                statement.execute(
                        "select app_realtime_enqueue_signal('stock:1', 'INSERT', '{}'::jsonb)");
                statement.execute("rollback to savepoint s");
            }

            assertThat(queryLong(connection,
                    "select count(*) from public.app_realtime_control where id = 1"))
                    .isEqualTo(1);
            call(connection, "stock:8", "INSERT", "{\"price\":2}");
            connection.commit();
        }

        assertThat(count("select count(*) from public.realtime_signal_outbox "
                + "where topic = 'stock:8'")).isEqualTo(1);
    }

    @Test
    void publicCannotExecuteEnqueueFunction() throws SQLException {
        List<Long> publicGrantees = numbers("select acl.grantee from pg_proc p "
                + "cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl "
                + "where p.pronamespace = 'public'::regnamespace "
                + "and p.proname = 'app_realtime_enqueue_signal' and p.pronargs = 3 "
                + "and acl.grantee = 0");

        assertThat(publicGrantees).isEmpty();
        assertThat(count("select count(*) from pg_proc p "
                + "where p.pronamespace = 'public'::regnamespace "
                + "and p.proname = 'app_realtime_enqueue_signal' and p.pronargs = 3"))
                .isEqualTo(1);
    }

    @Test
    void testRunsInScratchDatabase() throws SQLException {
        assertThat(scalar("select current_database()"))
                .asString()
                .startsWith("verify_event_foundation_");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
    }

    private static void setControl(boolean enabled, long budget) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "update public.app_realtime_control "
                             + "set enabled = ?, remaining_message_budget = ? where id = 1")) {
            statement.setBoolean(1, enabled);
            statement.setLong(2, budget);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void call(Connection connection, String topic, String event, String payload)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select public.app_realtime_enqueue_signal(?, ?, ?::jsonb)")) {
            statement.setString(1, topic);
            statement.setString(2, event);
            statement.setString(3, payload);
            statement.execute();
        }
    }

    private static long count(String sql, Object... parameters) throws SQLException {
        return ((Number) scalar(sql, parameters)).longValue();
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).as("query should return a row").isTrue();
            return resultSet.getLong(1);
        }
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
}
