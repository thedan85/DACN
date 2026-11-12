package com.project.dacn.db;

import com.project.dacn.support.ExternalPostgresDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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

class InventoryMigrationPostgresTest {

    private static ExternalPostgresDatabase database;
    private static String scratchDatabaseName;
    private static List<String> originalStock;
    private static List<String> originalOrders;

    @BeforeAll
    static void migrateAcrossInventoryBoundary() throws SQLException {
        database = ExternalPostgresDatabase.create();
        scratchDatabaseName = URI.create(database.jdbcUrl().substring("jdbc:".length()))
                .getPath().substring(1);
        verifyIdentity();
        System.out.println("Verified inventory scratch identity: " + scratchDatabaseName);
        Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password())
                .locations("classpath:db/migration").target("30").cleanDisabled(true).loggers()
                .load().migrate();
        verifyIdentity();
        execute("insert into roles (role_id, role_code, role_name) "
                + "values ('ROLE_inventory_fixture', 'inventory_fixture', 'Fixture')");
        execute("insert into users (user_id, user_email, user_password_hash, role_id) "
                + "select 'USR_inventory_fixture', 'inventory-fixture@example.invalid', 'unused', id "
                + "from roles where role_id = 'ROLE_inventory_fixture'");
        execute("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                + "select 'CUS_inventory_fixture', id, 'Fixture', '000' "
                + "from users where user_id = 'USR_inventory_fixture'");
        execute("insert into orders (order_id, customer_id, order_status, order_total, "
                + "order_address, order_recipient_name, order_phone) "
                + "select 'ORD_inventory_' || state || '_' || method, c.id, state, 10, "
                + "'fixture', 'Fixture', '000' from customers c "
                + "cross join unnest(array['PENDING','CONFIRMED','SHIPPING','COMPLETED','CANCELLED']) state "
                + "cross join unnest(array['COD','BANK_TRANSFER']) method "
                + "where c.customer_id = 'CUS_inventory_fixture'");
        execute("insert into payments (payment_id, order_id, payment_method, payment_amount) "
                + "select 'PAY_' || order_id, id, "
                + "case when order_id like '%_COD' then 'COD' else 'BANK_TRANSFER' end, 10 "
                + "from orders where order_id like 'ORD_inventory_%'");
        originalStock = strings("select id || ':' || variant_stock from variants order by id");
        originalOrders = strings("select o.order_id || ':' || o.order_status || ':' "
                + "|| p.payment_method || ':' || p.payment_status from orders o "
                + "join payments p on p.order_id = o.id where o.order_id like 'ORD_inventory_%' "
                + "order by o.id");
        assertThat(originalStock).isNotEmpty();
        assertThat(originalOrders).hasSize(10);
        database.migrate();
    }

    @AfterAll
    static void dropOnlyOwnedDatabase() throws SQLException {
        if (database != null) {
            dropAndVerify(database, scratchDatabaseName);
        }
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
        System.out.println("Confirmed inventory scratch cleanup: " + ownedName);
    }

    @Test
    void fixtureSetupFailureStillCleansOnlyItsOwnedDatabase() throws SQLException {
        assertThatThrownBy(() -> {
            ExternalPostgresDatabase failedSetup = ExternalPostgresDatabase.create();
            String ownedName = URI.create(failedSetup.jdbcUrl().substring("jdbc:".length()))
                    .getPath().substring(1);
            try {
                assertThat(ownedName).matches("verify_event_foundation_[0-9a-f]{12}");
                assertThat(ownedName).isNotEqualTo(scratchDatabaseName);
                try (Connection connection = DriverManager.getConnection(failedSetup.jdbcUrl(),
                        failedSetup.username(), failedSetup.password())) {
                    assertThat(strings(connection, "select current_database()")).containsExactly(ownedName);
                    System.out.println("Verified inventory failed-setup scratch identity: " + ownedName);
                    execute(connection, "insert into inventory_missing_fixture_table values (1)");
                }
            } finally {
                dropAndVerify(failedSetup, ownedName);
            }
        }).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42P01");
        verifyIdentity();
    }

    @Test
    void additiveMigrationPreservesLegacyOrdersCodPaymentsAndStock() throws SQLException {
        assertColumn("orders", "payment_expires_at", "timestamp with time zone", "YES", null);
        assertColumn("orders", "stock_released_at", "timestamp with time zone", "YES", null);
        assertColumn("orders", "cancellation_reason", "character varying", "YES", null);
        assertColumn("variants", "stock_revision", "bigint", "NO", "0");
        assertThat(strings("select character_maximum_length::text from information_schema.columns "
                + "where table_schema = 'public' and table_name = 'orders' "
                + "and column_name = 'cancellation_reason'")).containsExactly("32");
        assertThat(strings("select id || ':' || variant_stock from variants order by id"))
                .containsExactlyElementsOf(originalStock);
        assertThat(strings("select o.order_id || ':' || o.order_status || ':' "
                + "|| p.payment_method || ':' || p.payment_status from orders o "
                + "join payments p on p.order_id = o.id where o.order_id like 'ORD_inventory_%' "
                + "order by o.id")).containsExactlyElementsOf(originalOrders);
        assertThat(strings("select order_id from orders where payment_expires_at is not null "
                + "or cancellation_reason is not null or stock_released_at is not null")).isEmpty();
        assertThat(strings("select id::text from variants where stock_revision <> 0")).isEmpty();
        assertThat(strings("select version from flyway_schema_history where version = '31' and success"))
                .containsExactly("31");
    }

    @Test
    void databaseRejectsUnknownReasonsAndNegativeOrNullRevisions() throws SQLException {
        assertColumn("orders", "cancellation_reason", "character varying", "YES", null);
        assertColumn("variants", "stock_revision", "bigint", "NO", "0");
        for (String reason : List.of("STAFF_REJECTED", "PAYMENT_EXPIRED", "PAYMENT_FAILED")) {
            inRollback(connection -> execute(connection,
                    "update orders set cancellation_reason = '" + reason
                            + "' where order_id = 'ORD_inventory_PENDING_COD'"));
        }
        assertThatThrownBy(() -> inRollback(connection -> execute(connection,
                "update orders set cancellation_reason = 'OTHER' "
                        + "where order_id = 'ORD_inventory_PENDING_COD'")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
        assertThatThrownBy(() -> inRollback(connection -> execute(connection,
                "update variants set stock_revision = -1")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
        assertThatThrownBy(() -> inRollback(connection -> execute(connection,
                "update variants set stock_revision = null")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23502");
    }

    @Test
    void actualExpiryIndexExcludesNullDeadlinesTerminalAndReleasedOrders() throws SQLException {
        List<String> definitions = strings("select indexdef from pg_indexes "
                + "where schemaname = 'public' and indexname = 'idx_orders_payment_expiry'");
        assertThat(definitions).as("partial inventory expiry index").hasSize(1);
        assertThat(definitions.getFirst()).contains("USING btree (payment_expires_at, id)")
                .contains("payment_expires_at IS NOT NULL", "stock_released_at IS NULL");
        String predicate = strings("select pg_get_expr(indpred, indrelid) from pg_index "
                + "where indexrelid = 'public.idx_orders_payment_expiry'::regclass").getFirst();
        System.out.println("Inventory expiry index: " + definitions.getFirst());
        inRollback(connection -> {
            execute(connection, "update orders set payment_expires_at = current_timestamp "
                    + "where order_id like 'ORD_inventory_%' and order_id not like '%_COD'");
            assertThat(strings(connection, "select order_id from orders where " + predicate
                    + " order by order_id")).containsExactly(
                    "ORD_inventory_CONFIRMED_BANK_TRANSFER", "ORD_inventory_PENDING_BANK_TRANSFER",
                    "ORD_inventory_SHIPPING_BANK_TRANSFER");
            execute(connection, "update orders set stock_released_at = current_timestamp "
                    + "where order_id = 'ORD_inventory_PENDING_BANK_TRANSFER'");
            assertThat(strings(connection, "select order_id from orders where " + predicate
                    + " order by order_id")).containsExactly(
                    "ORD_inventory_CONFIRMED_BANK_TRANSFER", "ORD_inventory_SHIPPING_BANK_TRANSFER");
        });
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
