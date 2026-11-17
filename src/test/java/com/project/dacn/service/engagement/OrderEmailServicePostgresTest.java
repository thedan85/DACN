package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.integration.mail.MailContentProperties;
import com.project.dacn.integration.mail.SmtpAttemptExecutor;
import com.project.dacn.integration.mail.SmtpAttemptExecutor.Target;
import com.project.dacn.repository.engagement.EmailDeliveryRepository;
import com.project.dacn.repository.engagement.MailEligibilityRepository;
import com.project.dacn.service.engagement.EmailDeliveryService.Claimed;
import com.project.dacn.service.engagement.EmailDeliveryService.ClaimResult;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.project.dacn.common.constant.PaymentMethod.COD;
import static com.project.dacn.common.constant.PaymentMethod.VIETQR;
import static com.project.dacn.event.OrderEventType.ORDER_PLACED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL + loopback SMTP proof of fresh eligibility and result mapping.
 * One invocation-owned scratch database; credentials stay in process env only.
 */
class OrderEmailServicePostgresTest {

    private static final String ORDER_ID = "ORD_t10_mail";

    private static ExternalPostgresDatabase database;
    private static String scratchDatabaseName;
    private static HikariDataSource dataSource;
    private static NamedParameterJdbcTemplate jdbc;
    private static EmailDeliveryService delivery;
    private static MailEligibilityRepository eligibility;
    private static MailViewBuilder views;
    private static OrderEmailService service;
    private static ScheduledThreadPoolExecutor watchdog;
    private static Semaphore slot;
    private static long userId;

    private CaptureSmtpServer capture;
    private OrderEmailService testService;

    @BeforeAll
    static void setUp() throws SQLException {
        database = ExternalPostgresDatabase.create();
        scratchDatabaseName = URI.create(database.jdbcUrl().substring("jdbc:".length()))
                .getPath().substring(1);
        try {
            verifyIdentity();
            database.migrate();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(database.jdbcUrl());
            config.setUsername(database.username());
            config.setPassword(database.password());
            config.setMaximumPoolSize(8);
            config.setPoolName("t10-mail");
            dataSource = new HikariDataSource(config);
            jdbc = new NamedParameterJdbcTemplate(dataSource);
            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            EmailDeliveryRepository ledger = new EmailDeliveryRepository(jdbc);
            delivery = new EmailDeliveryService(ledger, new OrderNotificationPolicy(), transactionManager);
            eligibility = new MailEligibilityRepository(jdbc);
            views = new MailViewBuilder(new MailContentProperties(
                    "http://localhost:3000", "no-reply@notifications.local", "mail.example.test"));
            watchdog = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "t10-watchdog");
                t.setDaemon(true);
                return t;
            });
            slot = new Semaphore(1);
            seed();
            userId = scalarLong("select id from users where user_id = 'USR_t10_cust'");
        } catch (Exception | AssertionError failure) {
            cleanup();
            throw failure;
        }
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        if (database != null) {
            String name = scratchDatabaseName;
            database.drop();
            try (Connection connection = DriverManager.getConnection(
                    System.getenv("TEST_POSTGRES_URL"),
                    System.getenv("TEST_POSTGRES_USERNAME"),
                    System.getenv("TEST_POSTGRES_PASSWORD"));
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select 1 from pg_database where datname = '" + name + "'")) {
                assertThat(rows.next()).as("exact invocation-owned database must be gone").isFalse();
            }
            System.out.println("Confirmed task10 scratch cleanup: " + name);
            database = null;
        }
    }

    @BeforeEach
    void reset() throws Exception {
        execute("delete from email_deliveries");
        execute("delete from notifications where notification_id like 'NOTI_t10_%'");
        execute("update orders set order_status = 'PENDING', stock_released_at = null, "
                + "payment_expires_at = clock_timestamp() + interval '10 minutes' "
                + "where order_id = '" + ORDER_ID + "'");
        execute("update payments set payment_status = 'PENDING' where order_id = "
                + "(select id from orders where order_id = '" + ORDER_ID + "')");
        execute("update users set user_status = 'ACTIVE' where id = " + userId);
        if (capture != null) {
            capture.close();
        }
        capture = new CaptureSmtpServer();
        testService = new OrderEmailService(delivery, eligibility, new OrderNotificationPolicy(),
                new SmtpAttemptExecutor(new Target("127.0.0.1", capture.port(), null, null, false),
                        watchdog, slot),
                views, new MailContentProperties(), new DataSourceTransactionManager(dataSource));
    }

    @Test
    void acceptedCommitsSentEvenWhenCloseFails() {
        capture.failQuit = true;
        process(event(ORDER_PLACED, VIETQR));

        Row row = row(currentEventId());
        assertThat(row.state()).isEqualTo("SENT");
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.completedAt()).isNotNull();
        assertThat(capture.messageReceived()).isTrue();
        assertThat(capture.connectionCount()).isEqualTo(1);
    }

    @Test
    void ambiguousPostConnectFailureQuarantinesUnknown() {
        capture.dropAfterMailFrom = true;
        process(event(ORDER_PLACED, VIETQR));

        Row row = row(currentEventId());
        assertThat(row.state()).isEqualTo("UNKNOWN");
        assertThat(row.failureCode()).isEqualTo("SMTP_ACCEPTANCE_AMBIGUOUS");
        assertThat(row.completedAt()).isNotNull();
        // Ambiguous acceptance must never be retried automatically.
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(capture.connectionCount()).isEqualTo(1);
    }

    @Test
    void preAcceptanceFailureUsesBoundedRetryThenRetainedFailed() {
        capture.rejectRecipient = true;
        OrderEvent event = event(ORDER_PLACED, VIETQR);

        process(event);
        assertThat(row(event.eventId()).state()).isEqualTo("PENDING");
        assertThat(row(event.eventId()).attemptCount()).isEqualTo(1);

        forceRetryDue(event.eventId());
        process(event);
        assertThat(row(event.eventId()).state()).isEqualTo("PENDING");
        assertThat(row(event.eventId()).attemptCount()).isEqualTo(2);

        forceRetryDue(event.eventId());
        process(event);
        Row finalRow = row(event.eventId());
        assertThat(finalRow.state()).isEqualTo("FAILED");
        assertThat(finalRow.attemptCount()).isEqualTo(3);
        assertThat(finalRow.failureCode()).isEqualTo("SMTP_PRE_ACCEPTANCE");
        assertThat(capture.connectionCount()).isEqualTo(3);
    }

    @Test
    void retryAfterOnlineConfirmationSkipsWithZeroFurtherSmtp() {
        capture.rejectRecipient = true;
        OrderEvent receipt = event(ORDER_PLACED, VIETQR);
        seedHistoricalInApp(receipt);
        process(receipt);
        assertThat(row(receipt.eventId()).state()).isEqualTo("PENDING");
        assertThat(capture.connectionCount()).isEqualTo(1);
        String historical = historicalMessage();

        // Confirmation is processed first: both statuses leave PENDING.
        execute("update orders set order_status = 'CONFIRMED' where order_id = '" + ORDER_ID + "'");
        execute("update payments set payment_status = 'CONFIRMED' where order_id = "
                + "(select id from orders where order_id = '" + ORDER_ID + "')");
        forceRetryDue(receipt.eventId());
        process(receipt);

        Row row = row(receipt.eventId());
        assertThat(row.state()).isEqualTo("SKIPPED");
        assertThat(row.attemptCount()).isEqualTo(2);
        assertThat(row.completedAt()).isNotNull();
        assertThat(capture.connectionCount()).isEqualTo(1); // zero further SMTP
        assertThat(historicalMessage()).isEqualTo(historical);
    }

    @Test
    void retryAfterCodStaffAcceptanceSkipsWithZeroFurtherSmtp() {
        capture.rejectRecipient = true;
        OrderEvent receipt = event(ORDER_PLACED, COD);
        process(receipt);
        assertThat(capture.connectionCount()).isEqualTo(1);

        // Staff acceptance: order confirmed, payment still pending for COD.
        execute("update orders set order_status = 'CONFIRMED' where order_id = '" + ORDER_ID + "'");
        forceRetryDue(receipt.eventId());
        process(receipt);

        assertThat(row(receipt.eventId()).state()).isEqualTo("SKIPPED");
        assertThat(capture.connectionCount()).isEqualTo(1);
    }

    @Test
    void onlineJustBeforeDeadlineSendsAndNeverMutatesOrderOrStock() {
        setExpiry("clock_timestamp() + interval '1 second'");
        String before = stateSnapshot();
        process(event(ORDER_PLACED, VIETQR));

        assertThat(row(currentEventId()).state()).isEqualTo("SENT");
        assertThat(capture.connectionCount()).isEqualTo(1);
        assertThat(stateSnapshot()).isEqualTo(before);
    }

    @Test
    void onlineAtDeadlineSkipsWithZeroSmtp() {
        setExpiry("clock_timestamp()"); // read a few ms later => equality/past
        String before = stateSnapshot();
        process(event(ORDER_PLACED, VIETQR));

        assertThat(row(currentEventId()).state()).isEqualTo("SKIPPED");
        assertThat(row(currentEventId()).attemptCount()).isZero();
        assertThat(capture.connectionCount()).isZero();
        assertThat(stateSnapshot()).isEqualTo(before);
    }

    @Test
    void onlineAfterDeadlineSkipsWithZeroSmtp() {
        setExpiry("clock_timestamp() - interval '1 second'");
        process(event(ORDER_PLACED, VIETQR));

        assertThat(row(currentEventId()).state()).isEqualTo("SKIPPED");
        assertThat(capture.connectionCount()).isZero();
    }

    @Test
    void nullAndInactiveOnlineDeadlinesSkipWithZeroSmtp() {
        setExpiry("null");
        process(event(ORDER_PLACED, VIETQR));
        assertThat(row(currentEventId()).state()).isEqualTo("SKIPPED");
        assertThat(capture.connectionCount()).isZero();

        execute("update orders set payment_expires_at = clock_timestamp() + interval '10 minutes', "
                + "stock_released_at = clock_timestamp() where order_id = '" + ORDER_ID + "'");
        process(event(ORDER_PLACED, VIETQR));
        Row inactive = row(currentEventId());
        assertThat(inactive.state()).isEqualTo("SKIPPED");
        assertThat(capture.connectionCount()).isZero();
    }

    @Test
    void codWithNullDeadlineStillSendsToLocalCapture() {
        setExpiry("null");
        process(event(ORDER_PLACED, COD));

        assertThat(row(currentEventId()).state()).isEqualTo("SENT");
        assertThat(capture.connectionCount()).isEqualTo(1);
        assertThat(capture.messageReceived()).isTrue();
    }

    @Test
    void staleAndFutureEventsQuarantineWithZeroSmtp() {
        OrderEvent stale = OrderEvent.create(ORDER_PLACED, ORDER_ID, 1L,
                Instant.now().minus(31, ChronoUnit.DAYS), userId, OrderStatus.PENDING,
                PaymentStatus.PENDING, VIETQR, new BigDecimal("10.00"), null, "t10", "TEST");
        process(stale);
        Row row = row(stale.eventId());
        assertThat(row.state()).isEqualTo("UNKNOWN");
        assertThat(row.failureCode()).isEqualTo("EVENT_WINDOW_INVALID");
        assertThat(capture.connectionCount()).isZero();

        OrderEvent future = OrderEvent.create(ORDER_PLACED, ORDER_ID, 1L,
                Instant.now().plus(1, ChronoUnit.DAYS), userId, OrderStatus.PENDING,
                PaymentStatus.PENDING, VIETQR, new BigDecimal("10.00"), null, "t10", "TEST");
        process(future);
        assertThat(row(future.eventId()).state()).isEqualTo("UNKNOWN");
        assertThat(capture.connectionCount()).isZero();
    }

    @Test
    void inactiveRecipientSkipsWithZeroSmtp() {
        execute("update users set user_status = 'LOCKED' where id = " + userId);
        process(event(ORDER_PLACED, VIETQR));

        Row row = row(currentEventId());
        assertThat(row.state()).isEqualTo("SKIPPED");
        assertThat(capture.connectionCount()).isZero();
    }

    @Test
    void eligibilityTransactionIsReleasedBeforeSmtp() throws Exception {
        capture.delayGreetingMs = 1_500;
        OrderEvent event = event(ORDER_PLACED, VIETQR);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                process(event);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                returned.countDown();
            }
        }, "t10-send");
        worker.setDaemon(true);
        worker.start();

        assertThat(capture.awaitFirstConnection(5_000)).isTrue();
        // While SMTP is still open, a separate connection can lock the delivery row nowait.
        boolean lockFree;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select state from email_deliveries where event_id = '" + event.eventId()
                             + "' for update nowait")) {
            lockFree = rows.next() && "IN_FLIGHT".equals(rows.getString("state"));
        }
        assertThat(lockFree).as("no database transaction may be held across SMTP").isTrue();
        assertThat(returned.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(row(event.eventId()).state()).isEqualTo("SENT");
        assertThat(watchdog.getQueue()).isEmpty();
        assertThat(slot.availablePermits()).isEqualTo(1);
    }

    // ----- helpers -----

    private UUID currentEventId() {
        return jdbc.getJdbcOperations().queryForObject(
                "select event_id from email_deliveries order by id desc limit 1", UUID.class);
    }

    private void process(OrderEvent event) {
        testService.process(event);
    }

    private static OrderEvent event(OrderEventType type, PaymentMethod method) {
        return OrderEvent.create(type, ORDER_ID, 1L, Instant.now().minusSeconds(30), userId,
                OrderStatus.PENDING, PaymentStatus.PENDING, method,
                new BigDecimal("10.00"), reasonFor(type), "t10", "TEST");
    }

    private static OrderEventReason reasonFor(OrderEventType type) {
        return switch (type) {
            case ORDER_REJECTED -> OrderEventReason.STAFF_REJECTED;
            case ORDER_EXPIRED -> OrderEventReason.PAYMENT_EXPIRED;
            case PAYMENT_FAILED -> OrderEventReason.PAYMENT_FAILED;
            default -> null;
        };
    }

    private static void forceRetryDue(UUID eventId) {
        execute("update email_deliveries set next_eligible_at = "
                + "clock_timestamp() - interval '1 second' where event_id = '" + eventId + "'");
    }

    private static void setExpiry(String expression) {
        execute("update orders set payment_expires_at = " + expression
                + " where order_id = '" + ORDER_ID + "'");
    }

    private void seedHistoricalInApp(OrderEvent event) {
        execute("insert into notifications (notification_id, user_id, notification_message, "
                + "notification_type, notification_status, source_event_id, order_id, event_type, "
                + "order_sequence, occurred_at, recipient_scope) values "
                + "('NOTI_t10_" + event.eventId() + "', " + userId + ", 'historical receipt', "
                + "'ORDER', 'READ', '" + event.eventId() + "', "
                + "(select id from orders where order_id = '" + ORDER_ID + "'), "
                + "'ORDER_PLACED', 1, '" + event.occurredAt() + "', 'CUSTOMER')");
    }

    private static String historicalMessage() {
        return jdbc.getJdbcOperations().queryForObject(
                "select notification_message from notifications where notification_id like 'NOTI_t10_%'",
                String.class);
    }

    private static String stateSnapshot() {
        return jdbc.getJdbcOperations().queryForObject("""
                select o.order_status || '|' || p.payment_status || '|' ||
                       coalesce(o.payment_expires_at::text, 'null') || '|' ||
                       coalesce(o.stock_released_at::text, 'null') || '|' ||
                       (select count(*) from variants)
                  from orders o join payments p on p.order_id = o.id
                 where o.order_id = 'ORD_t10_mail'
                """, String.class);
    }

    private record Row(String state, int attemptCount, Instant completedAt, String failureCode) {
    }

    private static Row row(UUID eventId) {
        java.util.List<Row> rows = jdbc.query("""
                select state, attempt_count, completed_at, failure_code
                  from email_deliveries where event_id = :eventId
                """, new MapSqlParameterSource("eventId", eventId), (result, index) -> new Row(
                result.getString("state"), result.getInt("attempt_count"),
                instant(result, "completed_at"), result.getString("failure_code")));
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void execute(String sql) {
        jdbc.getJdbcOperations().execute(sql);
    }

    private static long scalarLong(String sql) {
        Long value = jdbc.getJdbcOperations().queryForObject(sql, Long.class);
        assertThat(value).isNotNull();
        return value;
    }

    private static void verifyIdentity() throws SQLException {
        assertThat(scratchDatabaseName).matches("verify_event_foundation_[0-9a-f]{12}");
        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select current_database()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(scratchDatabaseName);
        }
        System.out.println("Verified task10 scratch identity: " + scratchDatabaseName);
    }

    private static void seed() {
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select 'USR_t10_cust', 't10_cust@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'customer'");
        execute("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                + "select 'CUS_t10_cust', id, 'Fixture', '000' "
                + "from users where user_id = 'USR_t10_cust'");
        execute("insert into orders (order_id, customer_id, order_status, order_total, "
                + "order_address, order_recipient_name, order_phone, payment_expires_at) "
                + "select '" + ORDER_ID + "', id, 'PENDING', 10, 'fixture', 'Fixture', '000', "
                + "clock_timestamp() + interval '10 minutes' "
                + "from customers where customer_id = 'CUS_t10_cust'");
        execute("insert into payments (payment_id, order_id, payment_method, "
                + "payment_status, payment_amount) "
                + "select 'PAY_t10', id, 'VIETQR', 'PENDING', 10 "
                + "from orders where order_id = '" + ORDER_ID + "'");
    }
}
