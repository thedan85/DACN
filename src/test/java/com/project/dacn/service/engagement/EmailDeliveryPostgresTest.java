package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.engagement.EmailDeliveryState;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.repository.engagement.EmailDeliveryRepository;
import com.project.dacn.service.engagement.EmailDeliveryService.Busy;
import com.project.dacn.service.engagement.EmailDeliveryService.Claim;
import com.project.dacn.service.engagement.EmailDeliveryService.ClaimResult;
import com.project.dacn.service.engagement.EmailDeliveryService.Claimed;
import com.project.dacn.service.engagement.EmailDeliveryService.Completed;
import com.project.dacn.service.engagement.EmailDeliveryService.ReviewRequired;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.project.dacn.common.constant.PaymentMethod.COD;
import static com.project.dacn.common.constant.PaymentMethod.VIETQR;
import static com.project.dacn.event.OrderEventType.ORDER_COMPLETED;
import static com.project.dacn.event.OrderEventType.ORDER_CONFIRMED;
import static com.project.dacn.event.OrderEventType.ORDER_PLACED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL proof of the fenced delivery ledger. Each class run owns a
 * generated {@code verify_event_foundation_*} scratch database; credentials stay
 * in the process environment and the exact database is verified absent at close.
 */
class EmailDeliveryPostgresTest {

    private static final String ORDER_ID = "ORD_t8_email";
    private static ExternalPostgresDatabase database;
    private static String scratchDatabaseName;
    private static HikariDataSource dataSource;
    private static NamedParameterJdbcTemplate jdbc;
    private static EmailDeliveryService service;
    private static long userId;

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
            config.setPoolName("t8-email-ledger");
            dataSource = new HikariDataSource(config);
            jdbc = new NamedParameterJdbcTemplate(dataSource);
            DataSourceTransactionManager transactionManager =
                    new DataSourceTransactionManager(dataSource);
            service = new EmailDeliveryService(new EmailDeliveryRepository(jdbc),
                    new OrderNotificationPolicy(), transactionManager);
            seed();
            userId = scalarLong("select id from users where user_id = 'USR_t8_cust'");
        } catch (Exception | AssertionError failure) {
            cleanup();
            throw failure;
        }
    }

    @AfterAll
    static void cleanup() throws SQLException {
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
            System.out.println("Confirmed task8 scratch cleanup: " + name);
            database = null;
        }
    }

    @BeforeEach
    void reset() {
        execute("delete from email_deliveries");
        execute("update orders set order_status = 'PENDING', stock_released_at = null, "
                + "payment_expires_at = clock_timestamp() + interval '10 minutes' "
                + "where order_id = '" + ORDER_ID + "'");
        execute("update payments set payment_status = 'PENDING' where order_id = "
                + "(select id from orders where order_id = '" + ORDER_ID + "')");
    }

    @Test
    void competingClaimsYieldOneOwnerAndOneBusy() throws Exception {
        OrderEvent event = event(ORDER_PLACED, VIETQR);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ClaimResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return service.claim(event, userId);
                }));
            }
            start.countDown();
            List<ClaimResult> outcomes = results.stream().map(future -> {
                try {
                    return future.get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            pool.shutdownNow();

            assertThat(outcomes).extracting(Object::getClass)
                    .containsExactlyInAnyOrder(Claimed.class, Busy.class);
            assertThat(scalarLong("select count(*) from email_deliveries "
                    + "where event_id = '" + event.eventId() + "'")).isEqualTo(1);
            Row row = row(event.eventId());
            assertThat(row.state()).isEqualTo("IN_FLIGHT");
            assertThat(row.attemptCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void claimCommitsBeforeReturningAndAnchorIsMonotonic() throws Exception {
        OrderEvent event = event(ORDER_PLACED, VIETQR);
        Claim first = claim(event);
        UUID eventId = first.eventId();

        // A separate connection can lock the row now; no open claim transaction remains.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select state, claim_token from email_deliveries "
                                 + "where event_id = '" + eventId + "' for update nowait")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("state")).isEqualTo("IN_FLIGHT");
                assertThat(rows.getString("claim_token")).isEqualTo(first.claimToken().toString());
            } finally {
                connection.rollback();
            }
        }
        assertThat(first.leaseExpiresAt()).isAfter(Instant.now());
        assertThat(first.commitAnchor()).isPositive();

        assertThat(service.completeSent(new Claim(first.deliveryId(), eventId, userId,
                UUID.randomUUID(), first.leaseExpiresAt(), first.attempt(),
                first.commitAnchor()))).isFalse();

        assertThat(service.completeSent(first)).isTrue();
        forceRetryDue(eventId);
        execute("update email_deliveries set state = 'PENDING', completed_at = null "
                + "where event_id = '" + eventId + "'");
        Claim second = claim(event);
        assertThat(second.commitAnchor()).isGreaterThan(first.commitAnchor());
        assertThat(second.attempt()).isEqualTo(2);
    }

    @Test
    void duplicateSentAndSkippedReturnCompletedWithoutNewRows() {
        OrderEvent event = event(ORDER_PLACED, VIETQR);
        Claim claim = claim(event);
        assertThat(service.completeSent(claim)).isTrue();
        assertThat(row(event.eventId()).state()).isEqualTo("SENT");

        ClaimResult replay = service.claim(event, userId);
        assertThat(replay).isInstanceOf(Completed.class);
        assertThat(((Completed) replay).reason())
                .isEqualTo(EmailDeliveryService.Completion.ALREADY_SENT);
        assertThat(((Completed) replay).state()).isEqualTo(EmailDeliveryState.SENT);
        assertThat(row(event.eventId()).attemptCount()).isEqualTo(1);
        assertThat(scalarLong("select count(*) from email_deliveries "
                + "where event_id = '" + event.eventId() + "'")).isEqualTo(1);

        OrderEvent skippedEvent = event(ORDER_PLACED, VIETQR);
        Claim skippedClaim = claim(skippedEvent);
        assertThat(service.completeSkipped(skippedClaim)).isTrue();
        ClaimResult skipReplay = service.claim(skippedEvent, userId);
        assertThat(skipReplay).isInstanceOf(Completed.class);
        assertThat(((Completed) skipReplay).reason())
                .isEqualTo(EmailDeliveryService.Completion.ALREADY_SKIPPED);
        assertThat(row(skippedEvent.eventId()).state()).isEqualTo("SKIPPED");
        assertThat(row(skippedEvent.eventId()).attemptCount()).isEqualTo(1);
    }

    @Test
    void staleTokenCompletionCannotAffectLaterClaim() {
        OrderEvent event = event(ORDER_PLACED, VIETQR);
        Claim stale = claim(event);
        assertThat(service.recordTransientFailure(stale, "SMTP_TRANSIENT")).isTrue();
        forceRetryDue(event.eventId());
        Claim current = claim(event);
        assertThat(current.attempt()).isEqualTo(2);
        assertThat(current.claimToken()).isNotEqualTo(stale.claimToken());

        assertThat(service.completeSent(stale)).isFalse();
        Row afterStale = row(event.eventId());
        assertThat(afterStale.state()).isEqualTo("IN_FLIGHT");
        assertThat(afterStale.claimToken()).isEqualTo(current.claimToken().toString());
        assertThat(afterStale.attemptCount()).isEqualTo(2);

        assertThat(service.completeSent(current)).isTrue();
        assertThat(row(event.eventId()).state()).isEqualTo("SENT");
    }

    @Test
    void attemptCountAndRetryScheduleAreDurableUntilFailedReview() {
        OrderEvent event = event(ORDER_PLACED, VIETQR);
        Claim first = claim(event);
        assertThat(service.recordTransientFailure(first, "SMTP_TRANSIENT")).isTrue();
        Row retry = row(event.eventId());
        assertThat(retry.state()).isEqualTo("PENDING");
        assertThat(retry.attemptCount()).isEqualTo(1);
        assertThat(retry.nextEligibleAt()).isAfter(Instant.now().plusSeconds(8));
        assertThat(retry.failureCode()).isEqualTo("SMTP_TRANSIENT");

        forceRetryDue(event.eventId());
        Claim second = claim(event);
        assertThat(service.recordTransientFailure(second, "SMTP_TRANSIENT")).isTrue();
        assertThat(row(event.eventId()).nextEligibleAt())
                .isAfter(Instant.now().plusSeconds(28));

        forceRetryDue(event.eventId());
        Claim third = claim(event);
        assertThat(third.attempt()).isEqualTo(3);
        assertThat(service.recordTransientFailure(third, "SMTP_TRANSIENT")).isTrue();
        Row finalRow = row(event.eventId());
        assertThat(finalRow.state()).isEqualTo("FAILED");
        assertThat(finalRow.attemptCount()).isEqualTo(3);
        assertThat(finalRow.completedAt()).isNotNull();
        assertThat(finalRow.failureCode()).isEqualTo("SMTP_TRANSIENT");

        ClaimResult replay = service.claim(event, userId);
        assertThat(replay).isInstanceOf(ReviewRequired.class);
        assertThat(((ReviewRequired) replay).state()).isEqualTo(EmailDeliveryState.FAILED);
        assertThat(row(event.eventId()).attemptCount()).isEqualTo(3);
    }

    @Test
    void expiredInFlightBecomesUnknownAndNeverAuthorizesResend() {
        OrderEvent event = event(ORDER_PLACED, VIETQR);
        Claim claim = claim(event);
        expireLease(event.eventId());

        ClaimResult result = service.claim(event, userId);
        assertThat(result).isInstanceOf(ReviewRequired.class);
        assertThat(((ReviewRequired) result).state()).isEqualTo(EmailDeliveryState.UNKNOWN);
        Row unknown = row(event.eventId());
        assertThat(unknown.state()).isEqualTo("UNKNOWN");
        assertThat(unknown.completedAt()).isNotNull();
        assertThat(unknown.failureCode()).isEqualTo("LEASE_EXPIRED_UNKNOWN");
        assertThat(unknown.attemptCount()).isEqualTo(1);

        ClaimResult repeat = service.claim(event, userId);
        assertThat(repeat).isInstanceOf(ReviewRequired.class);
        assertThat(((ReviewRequired) repeat).state()).isEqualTo(EmailDeliveryState.UNKNOWN);
        assertThat(row(event.eventId())).isEqualTo(unknown);
        assertThat(scalarLong("select count(*) from email_deliveries "
                + "where event_id = '" + event.eventId() + "'")).isEqualTo(1);
    }

    @Test
    void unsupportedEmailPolicyAcknowledgesWithoutPointlessRow() {
        ClaimResult result = service.claim(event(ORDER_COMPLETED, VIETQR), userId);
        assertThat(result).isInstanceOf(Completed.class);
        assertThat(((Completed) result).reason())
                .isEqualTo(EmailDeliveryService.Completion.NO_EMAIL_ROW);
        assertThat(((Completed) result).state()).isNull();
        assertThat(scalarLong("select count(*) from email_deliveries")).isZero();
    }

    @Test
    void freshEligibilitySkipsOnlyRealEmailTriggersAndNeverOverwritesSent() {
        OrderEvent stale = event(ORDER_PLACED, VIETQR);
        execute("update orders set order_status = 'CONFIRMED' where order_id = '" + ORDER_ID + "'");
        ClaimResult skipped = service.claim(stale, userId);
        assertThat(skipped).isInstanceOf(Completed.class);
        assertThat(((Completed) skipped).reason())
                .isEqualTo(EmailDeliveryService.Completion.POLICY_SKIPPED);
        Row skippedRow = row(stale.eventId());
        assertThat(skippedRow.state()).isEqualTo("SKIPPED");
        assertThat(skippedRow.attemptCount()).isZero();
        assertThat(skippedRow.completedAt()).isNotNull();

        execute("update orders set order_status = 'PENDING' where order_id = '" + ORDER_ID + "'");
        OrderEvent sent = event(ORDER_PLACED, VIETQR);
        Claim claim = claim(sent);
        assertThat(service.completeSent(claim)).isTrue();
        execute("update orders set order_status = 'CANCELLED' where order_id = '" + ORDER_ID + "'");
        ClaimResult replay = service.claim(sent, userId);
        assertThat(replay).isInstanceOf(Completed.class);
        assertThat(((Completed) replay).state()).isEqualTo(EmailDeliveryState.SENT);
        Row sentRow = row(sent.eventId());
        assertThat(sentRow.state()).isEqualTo("SENT");
        assertThat(sentRow.attemptCount()).isEqualTo(1);
        assertThat(scalarLong("select count(*) from email_deliveries")).isEqualTo(2);
    }

    @Test
    void codReceiptWithNullDeadlineStillClaims() {
        OrderEvent event = event(ORDER_PLACED, COD);
        execute("update orders set payment_expires_at = null where order_id = '" + ORDER_ID + "'");
        Claim claim = claim(event);
        assertThat(claim.attempt()).isEqualTo(1);
        assertThat(row(event.eventId()).state()).isEqualTo("IN_FLIGHT");
    }

    @Test
    void cancelledConfirmationRecordsSkippedWithoutAttempt() {
        OrderEvent event = event(ORDER_CONFIRMED, VIETQR);
        execute("update orders set order_status = 'CANCELLED' where order_id = '" + ORDER_ID + "'");
        ClaimResult result = service.claim(event, userId);
        assertThat(result).isInstanceOf(Completed.class);
        assertThat(((Completed) result).state()).isEqualTo(EmailDeliveryState.SKIPPED);
        Row skipped = row(event.eventId());
        assertThat(skipped.state()).isEqualTo("SKIPPED");
        assertThat(skipped.attemptCount()).isZero();
        assertThat(skipped.completedAt()).isNotNull();
    }

    private static Claim claim(OrderEvent event) {
        ClaimResult result = service.claim(event, userId);
        assertThat(result).as("claim result for %s", event.eventId()).isInstanceOf(Claimed.class);
        return ((Claimed) result).claim();
    }

    private static OrderEvent event(OrderEventType type, PaymentMethod method) {
        return OrderEvent.create(type, ORDER_ID, 1L, Instant.now().minusSeconds(60), userId,
                OrderStatus.PENDING, PaymentStatus.PENDING, method, new BigDecimal("10.00"),
                reasonFor(type), "t8-corr", "TEST");
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

    private static void expireLease(UUID eventId) {
        execute("update email_deliveries set claim_expires_at = "
                + "clock_timestamp() - interval '1 second' "
                + "where event_id = '" + eventId + "' and state = 'IN_FLIGHT'");
    }

    private record Row(String state, int attemptCount, String claimToken,
                       Instant claimExpiresAt, Instant nextEligibleAt,
                       Instant completedAt, String failureCode) {
    }

    private static Row row(UUID eventId) {
        List<Row> rows = jdbc.query("""
                select state, attempt_count, claim_token, claim_expires_at,
                       next_eligible_at, completed_at, failure_code
                  from email_deliveries where event_id = :eventId
                """, new MapSqlParameterSource("eventId", eventId), (result, index) -> new Row(
                result.getString("state"), result.getInt("attempt_count"),
                result.getString("claim_token"), instant(result, "claim_expires_at"),
                instant(result, "next_eligible_at"), instant(result, "completed_at"),
                result.getString("failure_code")));
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
        System.out.println("Verified task8 scratch identity: " + scratchDatabaseName);
    }

    private static void seed() {
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select 'USR_t8_cust', 't8_cust@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'customer'");
        execute("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                + "select 'CUS_t8_cust', id, 'Fixture', '000' "
                + "from users where user_id = 'USR_t8_cust'");
        execute("insert into orders (order_id, customer_id, order_status, order_total, "
                + "order_address, order_recipient_name, order_phone, payment_expires_at) "
                + "select '" + ORDER_ID + "', id, 'PENDING', 10, 'fixture', 'Fixture', '000', "
                + "clock_timestamp() + interval '10 minutes' "
                + "from customers where customer_id = 'CUS_t8_cust'");
        execute("insert into payments (payment_id, order_id, payment_method, "
                + "payment_status, payment_amount) "
                + "select 'PAY_t8', id, 'VIETQR', 'PENDING', 10 "
                + "from orders where order_id = '" + ORDER_ID + "'");
    }
}
