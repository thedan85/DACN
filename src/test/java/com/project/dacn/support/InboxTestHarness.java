package com.project.dacn.support;

import com.project.dacn.repository.engagement.NotificationInboxQueries;
import com.project.dacn.service.engagement.NotificationInboxService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invocation-owned local PostgreSQL scratch database for the inbox snapshot
 * tests. Credentials live only in the process environment; the exact generated
 * database is verified before use and asserted absent after close.
 */
public final class InboxTestHarness implements AutoCloseable {

    public final ExternalPostgresDatabase database;
    public final String scratchDatabaseName;
    public final HikariDataSource dataSource;
    public final CountingDataSource counting;
    public final NamedParameterJdbcTemplate named;
    public final TransactionTemplate tx;
    public final NotificationInboxQueries queries;
    public final NotificationInboxService service;

    public final long orderSurrogateId;
    public final Map<String, Long> baselineRole = new HashMap<>();

    public static final String CUSTOMER = "USR_t4_cust";
    public static final String OTHER = "USR_t4_other";
    public static final String LOCKED = "USR_t4_locked";
    public static final String ADMIN = "USR_t4_admin";
    public static final String EMPLOYEE = "USR_t4_emp";
    public static final String NOPERM = "USR_t4_noperm";
    public static final String PERM = "USR_t4_perm";
    public static final String INACTIVE_PROFILE = "USR_t4_inactprof";

    private InboxTestHarness() throws SQLException {
        database = ExternalPostgresDatabase.create();
        scratchDatabaseName = URI.create(database.jdbcUrl().substring("jdbc:".length()))
                .getPath().substring(1);
        verifyIdentity();
        database.migrate();
        verifyIdentity();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(12);
        config.setPoolName("t4-inbox-harness");
        dataSource = new HikariDataSource(config);

        counting = new CountingDataSource(dataSource);
        named = new NamedParameterJdbcTemplate(counting);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(counting);
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        queries = new NotificationInboxQueries(named);
        service = new NotificationInboxService(queries, manager);

        seed();
        orderSurrogateId = scalarLongDirect("select id from orders where order_id = 'ORD_t4'");
    }

    public static InboxTestHarness create() throws SQLException {
        return new InboxTestHarness();
    }

    /** Reset mutable data and the query counter before each test. */
    public void reset() throws SQLException {
        counting.reset();
        execute("delete from notifications");
        execute("update users set inbox_revision = 0, user_status = 'ACTIVE'");
        execute("update users set user_status = 'LOCKED' where user_id = '" + LOCKED + "'");
        for (Map.Entry<String, Long> entry : baselineRole.entrySet()) {
            execute("update users set role_id = " + entry.getValue()
                    + " where user_id = '" + entry.getKey() + "'");
        }
        execute("update admins set admin_status = 'ACTIVE' where user_id in "
                + "(select id from users where user_id in ('" + ADMIN + "', '" + PERM
                + "', '" + NOPERM + "'))");
        execute("update admins set admin_status = 'INACTIVE' where user_id = "
                + "(select id from users where user_id = '" + INACTIVE_PROFILE + "')");
        execute("update employees set employee_status = 'ACTIVE' where user_id = "
                + "(select id from users where user_id = '" + EMPLOYEE + "')");
        execute("insert into role_permissions (role_permission_id, role_id, permission_id) "
                + "select 'rp-t4-perm', id, (select id from permissions "
                + "where lower(permission_code) = 'order:read') "
                + "from roles where role_id = 'role-t4-perm' "
                + "and not exists (select 1 from role_permissions where role_permission_id = 'rp-t4-perm')");
        execute("insert into role_permissions (role_permission_id, role_id, permission_id) "
                + "select 'rp-admin-order:read', id, (select id from permissions "
                + "where lower(permission_code) = 'order:read') "
                + "from roles where lower(role_code) = 'admin' "
                + "and not exists (select 1 from role_permissions "
                + "where role_permission_id = 'rp-admin-order:read')");
    }

    /** Insert an event-backed notification, fully populated (V37 all-or-none). */
    public void insertEvent(String userBusinessId, String scope, Instant occurredAt,
                            Long orderSequence, String status, String message) throws SQLException {
        execute("insert into notifications (notification_id, user_id, notification_message, "
                        + "notification_type, notification_status, source_event_id, order_id, "
                        + "event_type, order_sequence, occurred_at, recipient_scope) "
                        + "values ('NOTI_" + UUID.randomUUID() + "', "
                        + "(select id from users where user_id = '" + userBusinessId + "'), "
                        + "'" + message + "', 'ORDER', '" + status + "', '" + UUID.randomUUID() + "', "
                        + orderSurrogateId + ", 'ORDER_PLACED', " + orderSequence + ", '"
                        + Timestamp.from(occurredAt).toInstant() + "', '" + scope + "')");
    }

    /** Insert a legacy notification: every source field null, occurrence backfilled. */
    public void insertLegacy(String userBusinessId, Instant occurredAt,
                             String status, String message) throws SQLException {
        execute("insert into notifications (notification_id, user_id, notification_message, "
                        + "notification_type, notification_status, occurred_at) "
                        + "values ('NOTI_" + UUID.randomUUID() + "', "
                        + "(select id from users where user_id = '" + userBusinessId + "'), "
                        + "'" + message + "', 'SYSTEM', '" + status + "', '"
                        + Timestamp.from(occurredAt).toInstant() + "')");
    }

    public void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public long scalarLongDirect(String sql) throws SQLException {
        List<Long> values = longsDirect(sql);
        assertThat(values).as("scalar query %s", sql).hasSize(1);
        return values.getFirst();
    }

    public List<Long> longsDirect(String sql) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getLong(1));
            }
        }
        return values;
    }

    public List<String> stringsDirect(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private void seed() throws SQLException {
        execute("insert into roles (role_id, role_code, role_name, is_deleted) values "
                + "('role-t4-noperm', 't4noperm', 'T4 no permission', false), "
                + "('role-t4-perm', 't4perm', 'T4 permission', false)");
        execute("insert into role_permissions (role_permission_id, role_id, permission_id) "
                + "select 'rp-t4-perm', id, (select id from permissions "
                + "where lower(permission_code) = 'order:read') "
                + "from roles where role_id = 'role-t4-perm'");

        user(CUSTOMER, "customer");
        user(OTHER, "customer");
        user(LOCKED, "customer");
        user(ADMIN, "admin");
        user(EMPLOYEE, "employee");
        user(NOPERM, "t4noperm");
        user(PERM, "t4perm");
        user(INACTIVE_PROFILE, "admin");
        execute("update users set user_status = 'LOCKED' where user_id = '" + LOCKED + "'");

        profile("admins", "admin_id", "admin_name", "admin_status", ADMIN, "ACTIVE");
        profile("admins", "admin_id", "admin_name", "admin_status", PERM, "ACTIVE");
        profile("admins", "admin_id", "admin_name", "admin_status", INACTIVE_PROFILE, "INACTIVE");
        profile("admins", "admin_id", "admin_name", "admin_status", NOPERM, "ACTIVE");
        profile("employees", "employee_id", "employee_name", "employee_status", EMPLOYEE, "ACTIVE");

        execute("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                + "select 'CUS_t4', id, 'Fixture', '000' from users where user_id = '" + CUSTOMER + "'");
        execute("insert into orders (order_id, customer_id, order_status, order_total, "
                + "order_address, order_recipient_name, order_phone) "
                + "select 'ORD_t4', id, 'PENDING', 10, 'fixture', 'Fixture', '000' "
                + "from customers where customer_id = 'CUS_t4'");

        for (String id : List.of(CUSTOMER, OTHER, LOCKED, ADMIN, EMPLOYEE,
                NOPERM, PERM, INACTIVE_PROFILE)) {
            baselineRole.put(id, scalarLongDirect(
                    "select role_id from users where user_id = '" + id + "'"));
        }
    }

    private void user(String businessId, String roleCode) throws SQLException {
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select '" + businessId + "', '" + businessId.toLowerCase()
                + "@example.invalid', 'unused', 'ACTIVE', id from roles where lower(role_code) = '"
                + roleCode + "'");
    }

    private void profile(String table, String idColumn, String nameColumn,
                         String statusColumn, String businessId, String status) throws SQLException {
        execute("insert into " + table + " (" + idColumn + ", user_id, " + nameColumn + ", "
                + statusColumn + ") select '" + idColumn + "_" + businessId + "', id, 'Fixture', '"
                + status + "' from users where user_id = '" + businessId + "'");
    }

    private void verifyIdentity() throws SQLException {
        assertThat(scratchDatabaseName).matches("verify_event_foundation_[0-9a-f]{12}");
        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
             PreparedStatement statement = connection.prepareStatement("select current_database()");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(scratchDatabaseName);
        }
    }

    @Override
    public void close() throws SQLException {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
        database.drop();
        try (Connection connection = DriverManager.getConnection(
                System.getenv("TEST_POSTGRES_URL"),
                System.getenv("TEST_POSTGRES_USERNAME"),
                System.getenv("TEST_POSTGRES_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(
                     "select 1 from pg_database where datname = ?")) {
            statement.setString(1, scratchDatabaseName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("exact invocation-owned database must be gone").isFalse();
            }
        }
        System.out.println("Confirmed task4 scratch cleanup: " + scratchDatabaseName);
    }
}
