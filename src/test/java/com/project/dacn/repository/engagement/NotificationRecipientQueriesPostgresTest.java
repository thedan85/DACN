package com.project.dacn.repository.engagement;

import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.repository.engagement.NotificationRecipientQueries.CompanyRecipient;
import com.project.dacn.repository.engagement.NotificationRecipientQueries.CustomerRecipient;
import com.project.dacn.service.engagement.OrderNotificationPolicy;
import com.project.dacn.service.engagement.OrderNotificationPolicy.CurrentOrderState;
import com.project.dacn.support.ExternalPostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.project.dacn.common.constant.OrderStatus.PENDING;
import static com.project.dacn.common.constant.PaymentMethod.COD;
import static com.project.dacn.common.constant.PaymentMethod.VIETQR;
import static com.project.dacn.event.OrderEventType.ORDER_PLACED;
import static com.project.dacn.service.engagement.OrderNotificationPolicy.EmailEligibility.ELIGIBLE;
import static com.project.dacn.service.engagement.OrderNotificationPolicy.EmailEligibility.SKIPPED;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class NotificationRecipientQueriesPostgresTest {

    private static ExternalPostgresDatabase database;
    private static String scratchDatabaseName;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        database = ExternalPostgresDatabase.create();
        scratchDatabaseName = URI.create(database.jdbcUrl().substring("jdbc:".length()))
                .getPath().substring(1);
        verifyIdentity();
        database.migrate();
        verifyIdentity();
        seed();
    }

    @AfterAll
    static void dropOnlyOwnedDatabase() throws SQLException {
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
                    assertThat(rows.next()).as("exact invocation-owned database must be gone").isFalse();
                }
            }
            System.out.println("Confirmed task2 scratch cleanup: " + scratchDatabaseName);
        }
    }

    @Test
    void activeCustomerResolvedWithServerOwnedIdentity() throws SQLException {
        inRollback(connection -> {
            CustomerRecipient recipient = queriesOn(connection)
                    .customerRecipient("USR_t2_cust_ok").orElseThrow();
            assertThat(recipient.businessUserId()).isEqualTo("USR_t2_cust_ok");
            assertThat(recipient.userId()).isPositive();
            assertThat(recipient.email()).isEqualTo("usr_t2_cust_ok@example.invalid");
            assertThat(recipient.roleCode()).isEqualTo("customer");
        });
    }

    @Test
    void inactiveAndDeletedCustomersExcluded() throws SQLException {
        inRollback(connection -> {
            NotificationRecipientQueries queries = queriesOn(connection);
            assertThat(queries.customerRecipient("USR_t2_cust_locked")).isEmpty();
            assertThat(queries.customerRecipient("USR_t2_cust_deleted")).isEmpty();
        });
    }

    @Test
    void companyRecipientsAreActiveAuthorizedStaffWithoutEmail() throws SQLException {
        inRollback(connection -> {
            List<CompanyRecipient> recipients = queriesOn(connection).companyRecipients();
            assertThat(recipientIds(recipients)).containsExactlyInAnyOrder(
                    "USR_t2_admin_ok", "USR_t2_emp_ok", "USR_t2_admin_noprof");
            recipients.forEach(recipient -> {
                assertThat(recipient.userId()).isPositive();
                assertThat(recipient.roleCode()).isIn("admin", "employee");
            });
            assertThat(java.util.Arrays.stream(CompanyRecipient.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName))
                    .doesNotContain("email");
        });
    }

    @Test
    void roleCodeComparedCaseInsensitively() throws SQLException {
        inRollback(connection -> {
            execute(connection, "update roles set role_code = 'AdMiN' where role_id = 'role-admin'");
            execute(connection, "update roles set role_code = 'eMPLOYEE' where role_id = 'role-employee'");
            assertThat(recipientIds(queriesOn(connection).companyRecipients()))
                    .contains("USR_t2_admin_ok", "USR_t2_emp_ok", "USR_t2_admin_noprof");
        });
    }

    @Test
    void missingOrderReadPermissionExcluded() throws SQLException {
        inRollback(connection -> {
            assertThat(recipientIds(queriesOn(connection).companyRecipients()))
                    .doesNotContain("USR_t2_noperm");
        });
    }

    @Test
    void inactiveLinkedProfileExcluded() throws SQLException {
        inRollback(connection -> {
            assertThat(recipientIds(queriesOn(connection).companyRecipients()))
                    .doesNotContain("USR_t2_admin_inact", "USR_t2_emp_inact");
        });
    }

    @Test
    void absentLinkedProfileStillEligible() throws SQLException {
        inRollback(connection -> {
            assertThat(recipientIds(queriesOn(connection).companyRecipients()))
                    .contains("USR_t2_admin_noprof");
        });
    }

    @Test
    void lockedAccountAndDeletedRoleExcluded() throws SQLException {
        inRollback(connection -> {
            assertThat(recipientIds(queriesOn(connection).companyRecipients()))
                    .doesNotContain("USR_t2_admin_locked", "USR_t2_delrole");
        });
    }

    @Test
    void freshClockEligibilitySkipsAtDeadlineWithoutMutatingState() throws SQLException {
        inRollback(connection -> {
            assertThat(queriesOn(connection).customerRecipient("USR_t2_cust_ok")).isPresent();

            DatabaseClock clock = new DatabaseClock(
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
            Instant databaseNow = clock.now();
            assertThat(databaseNow).isAfter(Instant.parse("2026-03-01T00:00:00Z"));

            List<Instant> deadlines = instants(connection,
                    "select payment_expires_at from orders where order_id = 'ORD_t2_elig'");
            assertThat(deadlines).hasSize(1);
            Instant deadline = deadlines.getFirst();

            List<String> before = strings(connection,
                    "select o.order_status, p.payment_status, o.stock_released_at, "
                            + "o.cancellation_reason, o.payment_expires_at "
                            + "from orders o join payments p on p.order_id = o.id "
                            + "where o.order_id = 'ORD_t2_elig'");

            OrderNotificationPolicy policy = new OrderNotificationPolicy();
            OrderEvent placed = placed(VIETQR);
            CurrentOrderState current = new CurrentOrderState(PENDING, PaymentStatus.PENDING, deadline);

            assertThat(policy.emailEligibility(placed, current, true, databaseNow)).isEqualTo(ELIGIBLE);
            assertThat(policy.emailEligibility(placed, current, true, deadline)).isEqualTo(SKIPPED);
            assertThat(policy.emailEligibility(placed, current, true, deadline.plusNanos(1)))
                    .isEqualTo(SKIPPED);
            assertThat(policy.emailEligibility(placed,
                    new CurrentOrderState(PENDING, PaymentStatus.PENDING, null),
                    true, databaseNow)).isEqualTo(SKIPPED);
            assertThat(policy.emailEligibility(placed, current, false, databaseNow)).isEqualTo(SKIPPED);

            OrderEvent cod = placed(COD);
            assertThat(policy.emailEligibility(cod,
                    new CurrentOrderState(PENDING, PaymentStatus.PENDING, null),
                    false, deadline.plusSeconds(3600))).isEqualTo(ELIGIBLE);

            List<String> after = strings(connection,
                    "select o.order_status, p.payment_status, o.stock_released_at, "
                            + "o.cancellation_reason, o.payment_expires_at "
                            + "from orders o join payments p on p.order_id = o.id "
                            + "where o.order_id = 'ORD_t2_elig'");
            assertThat(after).isEqualTo(before);
        });
    }

    private static OrderEvent placed(com.project.dacn.common.constant.PaymentMethod method) {
        return OrderEvent.create(ORDER_PLACED, "ORD_t2_elig", 1,
                Instant.parse("2026-03-01T00:00:00Z"), 42L, PENDING, PaymentStatus.PENDING,
                method, new BigDecimal("10.00"), null, "t2-corr", "TEST");
    }

    private static NotificationRecipientQueries queriesOn(Connection connection) {
        return new NotificationRecipientQueries(new NamedParameterJdbcTemplate(
                new SingleConnectionDataSource(connection, true)));
    }

    private static Set<String> recipientIds(List<CompanyRecipient> recipients) {
        return recipients.stream().map(CompanyRecipient::businessUserId).collect(Collectors.toSet());
    }

    private static void seed() throws SQLException {
        execute("insert into roles (role_id, role_code, role_name, is_deleted) "
                + "values ('role-t2-noperm', 't2noperm', 'No permission', false), "
                + "('role-t2-delrole', 't2delrole', 'Deleted role', true)");
        execute("insert into role_permissions (role_permission_id, role_id, permission_id) "
                + "select 'rp-t2-delrole', id, (select id from permissions where permission_code = 'order:read') "
                + "from roles where role_id = 'role-t2-delrole'");

        for (String[] user : List.of(
                new String[]{"USR_t2_cust_ok", "customer", "ACTIVE"},
                new String[]{"USR_t2_cust_locked", "customer", "LOCKED"},
                new String[]{"USR_t2_cust_deleted", "customer", "DELETED"},
                new String[]{"USR_t2_admin_ok", "admin", "ACTIVE"},
                new String[]{"USR_t2_emp_ok", "employee", "ACTIVE"},
                new String[]{"USR_t2_admin_noprof", "admin", "ACTIVE"},
                new String[]{"USR_t2_admin_inact", "admin", "ACTIVE"},
                new String[]{"USR_t2_emp_inact", "employee", "ACTIVE"},
                new String[]{"USR_t2_noperm", "t2noperm", "ACTIVE"},
                new String[]{"USR_t2_delrole", "t2delrole", "ACTIVE"},
                new String[]{"USR_t2_admin_locked", "admin", "LOCKED"})) {
            execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                    + "select '" + user[0] + "', '" + user[0].toLowerCase() + "@example.invalid', "
                    + "'unused', '" + user[2] + "', id from roles where role_code = '" + user[1] + "'");
        }
        profile("admins", "admin_id", "admin_name", "admin_status", "USR_t2_admin_ok", "ACTIVE");
        profile("admins", "admin_id", "admin_name", "admin_status", "USR_t2_admin_inact", "INACTIVE");
        profile("admins", "admin_id", "admin_name", "admin_status", "USR_t2_admin_locked", "ACTIVE");
        profile("employees", "employee_id", "employee_name", "employee_status", "USR_t2_emp_ok", "ACTIVE");
        profile("employees", "employee_id", "employee_name", "employee_status", "USR_t2_emp_inact", "INACTIVE");
        profile("employees", "employee_id", "employee_name", "employee_status", "USR_t2_noperm", "ACTIVE");
        profile("admins", "admin_id", "admin_name", "admin_status", "USR_t2_delrole", "ACTIVE");

        execute("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                + "select 'CUS_t2_elig', id, 'Eligible', '000' from users "
                + "where user_id = 'USR_t2_cust_ok'");
        execute("insert into orders (order_id, customer_id, order_status, order_total, "
                + "order_address, order_recipient_name, order_phone, payment_expires_at) "
                + "select 'ORD_t2_elig', id, 'PENDING', 10, 'fixture', 'Fixture', '000', "
                + "clock_timestamp() + interval '1 hour' from customers where customer_id = 'CUS_t2_elig'");
        execute("insert into payments (payment_id, order_id, payment_method, payment_status, payment_amount) "
                + "select 'PAY_t2_elig', id, 'VIETQR', 'PENDING', 10 "
                + "from orders where order_id = 'ORD_t2_elig'");
    }

    private static void profile(String table, String idColumn, String nameColumn,
                                String statusColumn, String userId, String status) throws SQLException {
        execute("insert into " + table + " (" + idColumn + ", user_id, " + nameColumn + ", "
                + statusColumn + ") select '" + idColumn + "_" + userId + "', id, 'Fixture', '"
                + status + "' from users where user_id = '" + userId + "'");
    }

    private static void verifyIdentity() throws SQLException {
        assertThat(scratchDatabaseName).matches("verify_event_foundation_[0-9a-f]{12}");
        assertThat(strings("select current_database()")).containsExactly(scratchDatabaseName);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static List<Instant> instants(Connection connection, String sql) throws SQLException {
        List<Instant> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                Timestamp timestamp = rows.getTimestamp(1);
                values.add(timestamp == null ? null : timestamp.toInstant());
            }
        }
        return values;
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
