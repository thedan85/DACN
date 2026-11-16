package com.project.dacn.db;

import com.project.dacn.support.ExternalPostgresDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class OrderNotificationMigrationPostgresTest {

    private static final String USER_A = "USR_notif_a";
    private static final String USER_B = "USR_notif_b";
    private static final String ORDER_ID = "ORD_notif_1";
    private static final String OCCURRED = "'2026-03-01T00:00:00Z'::timestamptz";

    private static ExternalPostgresDatabase database;
    private static String scratchDatabaseName;
    private static String orderSurrogateId;
    private static String userAId;
    private static String userBId;

    @BeforeAll
    static void migrateAcrossNotificationBoundary() throws SQLException {
        database = ExternalPostgresDatabase.create();
        scratchDatabaseName = URI.create(database.jdbcUrl().substring("jdbc:".length()))
                .getPath().substring(1);
        verifyIdentity();
        System.out.println("Verified order-notification scratch identity: " + scratchDatabaseName);
        Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password())
                .locations("classpath:db/migration").target("36").cleanDisabled(true).loggers()
                .load().migrate();
        verifyIdentity();
        seedLegacyState();
        database.migrate();
        verifyIdentity();
        List<String> ids = strings("select id::text from orders where order_id = '" + ORDER_ID + "'");
        assertThat(ids).hasSize(1);
        orderSurrogateId = ids.getFirst();
    }

    @AfterAll
    static void dropOnlyOwnedDatabase() throws SQLException {
        if (database != null) {
            dropAndVerify(database, scratchDatabaseName);
        }
    }

    @Test
    void legacyOccurrenceTimeBackfilledAndNewColumnsInstalled() throws SQLException {
        assertColumn("users", "inbox_revision", "bigint", "NO", "0");
        assertColumn("notifications", "source_event_id", "uuid", "YES", null);
        assertColumn("notifications", "order_id", "bigint", "YES", null);
        assertColumn("notifications", "event_type", "character varying", "YES", null);
        assertColumn("notifications", "order_sequence", "bigint", "YES", null);
        assertColumn("notifications", "occurred_at", "timestamp with time zone", "YES", null);
        assertColumn("notifications", "recipient_scope", "character varying", "YES", null);

        assertThat(strings("select notification_id from notifications "
                + "where occurred_at is distinct from created_at")).isEmpty();
        assertThat(strings("select notification_id from notifications where source_event_id is not null "
                + "or order_id is not null or event_type is not null or order_sequence is not null "
                + "or recipient_scope is not null")).isEmpty();
        assertThat(strings("select version from flyway_schema_history "
                + "where version = '37' and success")).containsExactly("37");
    }

    @Test
    void eventBackedColumnsAreAllOrNoneAndLegacyRowsStillInsert() throws SQLException {
        inRollback(connection -> {
            execute(connection, insertEventBacked("NOTI_full", event("full"), orderSurrogateId,
                    "3", OCCURRED, "'CUSTOMER'", userAId));
            assertThat(strings(connection, "select notification_id from notifications "
                    + "where source_event_id = '" + event("full") + "'::uuid"))
                    .containsExactly("NOTI_full");
        });

        String missingScope = insertEventBacked("NOTI_ns", event("ns"), orderSurrogateId,
                "1", OCCURRED, "null", userAId);
        assertThatThrownBy(() -> inRollback(connection -> execute(connection, missingScope)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");

        String missingOccurred = insertEventBacked("NOTI_no", event("no"), orderSurrogateId,
                "1", "null", "'COMPANY'", userAId);
        assertThatThrownBy(() -> inRollback(connection -> execute(connection, missingOccurred)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");

        String missingOrder = insertEventBacked("NOTI_ord", event("ord"), "null",
                "1", OCCURRED, "'CUSTOMER'", userAId);
        assertThatThrownBy(() -> inRollback(connection -> execute(connection, missingOrder)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");

        String zeroSequence = insertEventBacked("NOTI_seq", event("seq"), orderSurrogateId,
                "0", OCCURRED, "'CUSTOMER'", userAId);
        assertThatThrownBy(() -> inRollback(connection -> execute(connection, zeroSequence)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");

        String badScope = insertEventBacked("NOTI_scope", event("scope"), orderSurrogateId,
                "1", OCCURRED, "'OTHER'", userAId);
        assertThatThrownBy(() -> inRollback(connection -> execute(connection, badScope)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");

        inRollback(connection -> {
            execute(connection, "insert into notifications (notification_id, user_id, notification_message) "
                    + "values ('NOTI_legacy_new', " + userAId + ", 'legacy still allowed')");
            assertThat(strings(connection, "select notification_id from notifications "
                    + "where notification_id = 'NOTI_legacy_new'")).containsExactly("NOTI_legacy_new");
        });
    }

    @Test
    void eventUniquenessIncludesSoftDeletedRows() throws SQLException {
        assertThatThrownBy(() -> inRollback(connection -> {
            execute(connection, insertEventBacked("NOTI_dup", event("dup"), orderSurrogateId,
                    "1", OCCURRED, "'CUSTOMER'", userAId));
            execute(connection, "update notifications set is_deleted = true "
                    + "where source_event_id = '" + event("dup") + "'::uuid");
            execute(connection, insertEventBacked("NOTI_dup_again", event("dup"), orderSurrogateId,
                    "1", OCCURRED, "'CUSTOMER'", userAId));
        })).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");

        inRollback(connection -> {
            execute(connection, insertEventBacked("NOTI_dup_a2", event("dup2"), orderSurrogateId,
                    "1", OCCURRED, "'CUSTOMER'", userAId));
            execute(connection, insertEventBacked("NOTI_dup_b", event("dup2"), orderSurrogateId,
                    "1", OCCURRED, "'CUSTOMER'", userBId));
            assertThat(strings(connection, "select user_id from notifications "
                    + "where source_event_id = '" + event("dup2") + "'::uuid order by user_id"))
                    .containsExactly(userAId, userBId);
        });
    }

    @Test
    void emailAndReceiptIdentitiesAreIndependent() throws SQLException {
        assertThatThrownBy(() -> inRollback(connection -> {
            execute(connection, "insert into email_deliveries (event_id, recipient_user_id, state) "
                    + "values ('" + event("mail") + "'::uuid, (select id from users where user_id = '"
                    + USER_A + "'), 'PENDING')");
            execute(connection, "insert into email_deliveries (event_id, recipient_user_id, state) "
                    + "values ('" + event("mail") + "'::uuid, (select id from users where user_id = '"
                    + USER_B + "'), 'PENDING')");
            execute(connection, "insert into notification_event_receipts (event_id, recipient_user_ids) "
                    + "values ('" + event("mail") + "'::uuid, '[]'::jsonb)");
            assertThat(strings(connection, "select count(*) from email_deliveries "
                    + "where event_id = '" + event("mail") + "'::uuid")).containsExactly("2");
            assertThat(strings(connection, "select count(*) from notification_event_receipts "
                    + "where event_id = '" + event("mail") + "'::uuid")).containsExactly("1");
            execute(connection, "insert into email_deliveries (event_id, recipient_user_id, state) "
                    + "values ('" + event("mail") + "'::uuid, (select id from users where user_id = '"
                    + USER_A + "'), 'PENDING')");
        })).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");

        assertThatThrownBy(() -> inRollback(connection -> execute(connection,
                "insert into notification_event_receipts (event_id, recipient_user_ids) "
                        + "values ('" + event("receipt") + "'::uuid, '[]'::jsonb);"
                        + "insert into notification_event_receipts (event_id, recipient_user_ids) "
                        + "values ('" + event("receipt") + "'::uuid, '[]'::jsonb)")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");

        assertThatThrownBy(() -> inRollback(connection -> execute(connection,
                "insert into email_deliveries (event_id, recipient_user_id, state) "
                        + "values ('" + event("state") + "'::uuid, (select id from users where user_id = '"
                        + USER_A + "'), 'BOGUS')")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
        assertThatThrownBy(() -> inRollback(connection -> execute(connection,
                "insert into email_deliveries (event_id, recipient_user_id, state, attempt_count) "
                        + "values ('" + event("tries") + "'::uuid, (select id from users where user_id = '"
                        + USER_A + "'), 'PENDING', -1)")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
    }

    @Test
    void dedupEvidenceSurvivesNotificationAndOutboxDeletion() throws SQLException {
        inRollback(connection -> {
            execute(connection, "insert into notification_event_receipts (event_id, recipient_user_ids) "
                    + "values ('" + event("nocascade") + "'::uuid, '[]'::jsonb)");
            execute(connection, "insert into email_deliveries (event_id, recipient_user_id, state) "
                    + "values ('" + event("nocascade") + "'::uuid, (select id from users where user_id = '"
                    + USER_A + "'), 'PENDING')");
            execute(connection, insertEventBacked("NOTI_nocascade", event("nocascade"),
                    orderSurrogateId, "1", OCCURRED, "'CUSTOMER'", userAId));
            execute(connection, "insert into event_outbox (event_id, order_id, order_sequence, "
                    + "event_type, schema_version, payload, available_at) "
                    + "values ('" + event("nocascade") + "'::uuid, " + orderSurrogateId
                    + ", 2, 'ORDER_CONFIRMED', 1, '{}'::jsonb, clock_timestamp())");

            execute(connection, "delete from notifications where source_event_id = '"
                    + event("nocascade") + "'::uuid");
            assertThat(strings(connection, "select count(*) from notification_event_receipts "
                    + "where event_id = '" + event("nocascade") + "'::uuid")).containsExactly("1");
            assertThat(strings(connection, "select count(*) from email_deliveries "
                    + "where event_id = '" + event("nocascade") + "'::uuid")).containsExactly("1");

            execute(connection, "delete from event_outbox where event_id = '"
                    + event("nocascade") + "'::uuid");
            assertThat(strings(connection, "select count(*) from notification_event_receipts "
                    + "where event_id = '" + event("nocascade") + "'::uuid")).containsExactly("1");
            assertThat(strings(connection, "select count(*) from email_deliveries "
                    + "where event_id = '" + event("nocascade") + "'::uuid")).containsExactly("1");
        });
    }

    @Test
    void namedConstraintsAreInstalledWithExpectedDefinitions() throws SQLException {
        List<String> definitions = strings("select conname || ':' || pg_get_constraintdef(oid) "
                + "from pg_constraint where conrelid in ('public.notifications'::regclass, "
                + "'public.email_deliveries'::regclass, 'public.notification_event_receipts'::regclass) "
                + "and conname in ('chk_notifications_event_backed_all_or_none', "
                + "'chk_notifications_order_sequence', 'chk_notifications_recipient_scope', "
                + "'uq_notifications_event_user', 'fk_notifications_order', "
                + "'uq_email_deliveries_event_recipient', 'chk_email_deliveries_state', "
                + "'uq_notification_event_receipts_event') order by conname");
        assertThat(definitions).hasSize(8);
        definitions.forEach(System.out::println);
        assertThat(definitions).anySatisfy(sql -> assertThat(sql)
                .contains("uq_notifications_event_user").contains("UNIQUE (source_event_id, user_id)"));
        assertThat(definitions).anySatisfy(sql -> assertThat(sql)
                .contains("fk_notifications_order").contains("FOREIGN KEY (order_id)")
                .contains("ON DELETE RESTRICT"));
        assertThat(definitions).anySatisfy(sql -> assertThat(sql)
                .contains("chk_notifications_recipient_scope")
                .contains("'CUSTOMER'::character varying")
                .contains("'COMPANY'::character varying"));
        assertThat(definitions).anySatisfy(sql -> assertThat(sql)
                .contains("uq_email_deliveries_event_recipient")
                .contains("UNIQUE (event_id, recipient_user_id)"));
        assertThat(definitions).anySatisfy(sql -> assertThat(sql)
                .contains("chk_email_deliveries_state")
                .contains("'IN_FLIGHT'::character varying")
                .contains("'UNKNOWN'::character varying"));
        assertThat(definitions).anySatisfy(sql -> assertThat(sql)
                .contains("uq_notification_event_receipts_event").contains("UNIQUE (event_id)"));
    }

    @Test
    void visibleInboxAndUnreadPartialIndexesHaveExactDefinitions() throws SQLException {
        List<String> definitions = strings("select indexdef from pg_indexes where schemaname = 'public' "
                + "and indexname in ('idx_notifications_inbox_visible', 'idx_notifications_unread') "
                + "order by indexname");
        assertThat(definitions).hasSize(2);
        definitions.forEach(System.out::println);
        String visible = definitions.stream()
                .filter(sql -> sql.contains("idx_notifications_inbox_visible")).findFirst().orElseThrow();
        assertThat(visible)
                .contains("USING btree (user_id, occurred_at DESC, order_sequence DESC NULLS LAST, id DESC)")
                .contains("is_deleted = false");
        String unread = definitions.stream()
                .filter(sql -> sql.contains("idx_notifications_unread")).findFirst().orElseThrow();
        assertThat(unread)
                .contains("USING btree (user_id)")
                .contains("(notification_status)::text = 'UNREAD'::text")
                .contains("is_deleted = false");
    }

    @Test
    void ordinaryUserInsertGetsRevisionZeroAndAuthStyleUpdateDoesNotMoveIt() throws SQLException {
        inRollback(connection -> {
            execute(connection, "insert into users (user_id, user_email, user_password_hash, role_id) "
                    + "select 'USR_notif_rev', 'notif-rev@example.invalid', 'unused', id "
                    + "from roles where role_id = 'ROLE_notif_fixture'");
            assertThat(strings(connection, "select inbox_revision::text from users "
                    + "where user_id = 'USR_notif_rev'")).containsExactly("0");

            Long target = Long.valueOf(strings(connection,
                    "select id::text from users where user_id = '" + USER_A + "'").getFirst());
            execute(connection, "update users set inbox_revision = inbox_revision + 1 where id = " + target);
            execute(connection, "update users set user_last_login = clock_timestamp() where id = " + target);
            assertThat(strings(connection, "select inbox_revision::text from users where id = " + target))
                    .containsExactly("1");
        });
    }

    private static void seedLegacyState() throws SQLException {
        execute("insert into roles (role_id, role_code, role_name) "
                + "values ('ROLE_notif_fixture', 'notif_fixture', 'Fixture')");
        for (String userId : List.of(USER_A, USER_B)) {
            execute("insert into users (user_id, user_email, user_password_hash, role_id) "
                    + "select '" + userId + "', '" + userId.toLowerCase() + "@example.invalid', "
                    + "'unused', id from roles where role_id = 'ROLE_notif_fixture'");
        }
        userAId = strings("select id::text from users where user_id = '" + USER_A + "'").getFirst();
        userBId = strings("select id::text from users where user_id = '" + USER_B + "'").getFirst();
        execute("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                + "select 'CUS_notif_a', id, 'Fixture', '000' from users where user_id = '" + USER_A + "'");
        execute("insert into orders (order_id, customer_id, order_status, order_total, "
                + "order_address, order_recipient_name, order_phone) "
                + "select '" + ORDER_ID + "', id, 'PENDING', 10, 'fixture', 'Fixture', '000' "
                + "from customers where customer_id = 'CUS_notif_a'");
        execute("insert into event_outbox (event_id, order_id, order_sequence, event_type, "
                + "schema_version, payload, available_at) "
                + "select '" + event("seed") + "'::uuid, id, 1, 'ORDER_PLACED', 1, "
                + "'{}'::jsonb, clock_timestamp() from orders where order_id = '" + ORDER_ID + "'");
        execute("insert into notifications (notification_id, user_id, notification_message, "
                + "created_at, updated_at) values "
                + "('NOTI_legacy_a', " + userAId + ", 'legacy a', "
                + "'2026-01-01T00:00:00Z'::timestamptz, '2026-01-01T00:00:00Z'::timestamptz), "
                + "('NOTI_legacy_b', " + userAId + ", 'legacy b', "
                + "'2026-02-01T00:00:00Z'::timestamptz, '2026-02-01T00:00:00Z'::timestamptz)");
    }

    private static String event(String suffix) {
        return "22222222-2222-4222-8222-" + String.format("%012d", suffix.hashCode() & 0xFFFFFFFFL);
    }

    private static String insertEventBacked(String notificationId, String eventId, String orderLiteral,
                                            String sequence, String occurredLiteral, String scope,
                                            String userId) {
        return "insert into notifications (notification_id, user_id, notification_message, "
                + "source_event_id, order_id, event_type, order_sequence, occurred_at, recipient_scope) "
                + "values ('" + notificationId + "', '" + userId + "', 'event', '" + eventId + "'::uuid, "
                + orderLiteral + ", 'ORDER_PLACED', " + sequence + ", " + occurredLiteral + ", " + scope + ")";
    }

    private static void assertColumn(String table, String name, String type, String nullable,
                                     String defaultValue) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "select data_type, is_nullable, column_default from information_schema.columns "
                             + "where table_schema = 'public' and table_name = ? and column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("requested column %s.%s exists", table, name).isTrue();
                assertThat(rows.getString(1)).isEqualTo(type);
                assertThat(rows.getString(2)).isEqualTo(nullable);
                assertThat(rows.getString(3)).isEqualTo(defaultValue);
            }
        }
    }

    private static void verifyIdentity() throws SQLException {
        assertThat(scratchDatabaseName).matches("verify_event_foundation_[0-9a-f]{12}");
        assertThat(strings("select current_database()")).containsExactly(scratchDatabaseName);
    }

    private static void dropAndVerify(ExternalPostgresDatabase ownedDatabase, String ownedName)
            throws SQLException {
        ownedDatabase.drop();
        try (Connection connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv("TEST_POSTGRES_USERNAME"), System.getenv("TEST_POSTGRES_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(
                     "select 1 from pg_database where datname = ?")) {
            statement.setString(1, ownedName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("exact invocation-owned database must be gone").isFalse();
            }
        }
        System.out.println("Confirmed order-notification scratch cleanup: " + ownedName);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect()) {
            execute(connection, sql);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static List<String> strings(String sql) throws SQLException {
        try (Connection connection = connect()) {
            return strings(connection, sql);
        }
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static void inRollback(SqlAction action) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                action.run(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run(Connection connection) throws SQLException;
    }
}
