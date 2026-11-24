package com.project.dacn.support;

import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import com.project.dacn.repository.engagement.NotificationFanoutRepository;
import com.project.dacn.repository.engagement.NotificationRecipientQueries;
import com.project.dacn.service.engagement.NotificationFanoutService;
import com.project.dacn.service.engagement.NotificationMessageRenderer;
import com.project.dacn.service.engagement.OrderNotificationPolicy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

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
import java.util.List;
import java.util.UUID;

import static com.project.dacn.event.OrderEventType.ORDER_PLACED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invocation-owned local PostgreSQL scratch database for the notification
 * fan-out tests. Credentials live only in the process environment; the exact
 * generated database is verified before use and asserted absent after close.
 */
public final class FanoutTestHarness implements AutoCloseable {

    public final ExternalPostgresDatabase database;
    public final String scratchDatabaseName;
    public final HikariDataSource dataSource;
    public final JdbcTemplate jdbc;
    public final NamedParameterJdbcTemplate named;
    public final TransactionTemplate tx;
    public final NotificationFanoutService service;
    public final NotificationRecipientQueries queries;
    public final NotificationFanoutRepository repository;
    public final DatabaseClock clock;

    public final long customerId;
    public final long adminId;
    public final long employeeId;
    public final String orderId = "ORD_t3_fixture";
    public final long orderSurrogateId;
    public final Instant dbNow;

    private FanoutTestHarness() throws SQLException {
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
        config.setMaximumPoolSize(8);
        config.setPoolName("t3-fanout-harness");
        dataSource = new HikariDataSource(config);

        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        clock = new DatabaseClock(jdbc);
        OrderEventAgeGuard ageGuard = new OrderEventAgeGuard(clock);
        repository = new NotificationFanoutRepository(named);
        queries = new NotificationRecipientQueries(named);
        service = new NotificationFanoutService(repository, queries, clock, ageGuard,
                new NotificationMessageRenderer(), new OrderNotificationPolicy());

        seed();
        customerId = scalarLong("select id from users where user_id = 'USR_t3_cust'");
        adminId = scalarLong("select id from users where user_id = 'USR_t3_admin'");
        employeeId = scalarLong("select id from users where user_id = 'USR_t3_emp'");
        orderSurrogateId = scalarLong("select id from orders where order_id = '" + orderId + "'");
        dbNow = clock.now();
    }

    public static FanoutTestHarness create() throws SQLException {
        return new FanoutTestHarness();
    }

    public OrderEvent event(UUID eventId, long customerInternalId, Instant occurredAt,
                            PaymentMethod method, String orderBusinessId) {
        return new OrderEvent(1, eventId, ORDER_PLACED, orderBusinessId, 1L, occurredAt,
                Long.toString(customerInternalId),
                com.project.dacn.common.constant.OrderStatus.PENDING,
                com.project.dacn.common.constant.PaymentStatus.PENDING,
                method, "10.00", null, "t3-corr", "TEST");
    }

    public OrderEvent freshEvent(UUID eventId, PaymentMethod method) {
        return event(eventId, customerId, dbNow.minusSeconds(60), method, orderId);
    }

    public void handle(OrderEvent event) {
        tx.executeWithoutResult(status -> service.handle(event));
    }

    public Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    public void execute(String sql) throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public List<Long> longs(String sql) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getLong(1));
            }
        }
        return values;
    }

    public long scalarLong(String sql) throws SQLException {
        List<Long> values = longs(sql);
        assertThat(values).as("scalar query %s", sql).hasSize(1);
        return values.getFirst();
    }

    public List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private void seed() throws SQLException {
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select 'USR_t3_cust', 't3_cust@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'customer'");
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select 'USR_t3_admin', 't3_admin@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'admin'");
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select 'USR_t3_emp', 't3_emp@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'employee'");
        execute("insert into admins (admin_id, user_id, admin_name, admin_status) "
                + "select 'ADM_t3_admin', id, 'Fixture', 'ACTIVE' "
                + "from users where user_id = 'USR_t3_admin'");
        execute("insert into employees (employee_id, user_id, employee_name, employee_status) "
                + "select 'EMP_t3_emp', id, 'Fixture', 'ACTIVE' "
                + "from users where user_id = 'USR_t3_emp'");
        execute("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                + "select 'CUS_t3_cust', id, 'Fixture', '000' "
                + "from users where user_id = 'USR_t3_cust'");
        execute("insert into orders (order_id, customer_id, order_status, order_total, "
                + "order_address, order_recipient_name, order_phone) "
                + "select '" + orderId + "', id, 'PENDING', 10, 'fixture', 'Fixture', '000' "
                + "from customers where customer_id = 'CUS_t3_cust'");
    }

    private void verifyIdentity() throws SQLException {
        assertThat(scratchDatabaseName).matches("verify_event_foundation_[0-9a-f]{12}");
        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
             PreparedStatement statement = connection.prepareStatement(
                     "select current_database()");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(scratchDatabaseName);
        }
    }

    @Override
    public void close() throws SQLException {
        if (dataSource != null && !dataSource.isClosed()) {
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
        System.out.println("Confirmed task3 scratch cleanup: " + scratchDatabaseName);
    }
}
