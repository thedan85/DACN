package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.integration.mail.MailContentProperties;
import com.project.dacn.integration.mail.SmtpAttemptExecutor;
import com.project.dacn.messaging.rabbit.consumer.EmailNotificationConsumer;
import com.project.dacn.messaging.rabbit.consumer.EmailTransportPause;
import com.project.dacn.messaging.rabbit.consumer.NotificationChannel;
import com.project.dacn.messaging.rabbit.consumer.NotificationConsumerSupport;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import com.project.dacn.messaging.rabbit.consumer.RabbitRetryPublisher;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.repository.engagement.EmailDeliveryRepository;
import com.project.dacn.repository.engagement.MailEligibilityRepository;
import com.project.dacn.service.engagement.EmailDeliveryService.Claim;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real PostgreSQL proof that an outcome-persistence failure after SMTP acceptance
 * leaves an unresolved ledger row, and that broker redelivery never starts a
 * replacement SMTP attempt or ACKs away the only recoverable work.
 * Invocation-owned identity-guarded scratch database; process-only credentials.
 */
class EmailConsumerOutcomePostgresTest {

    private static final String ORDER_ID = "ORD_t11_consumer";
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    private static ExternalPostgresDatabase database;
    private static String scratchDatabaseName;
    private static HikariDataSource dataSource;
    private static NamedParameterJdbcTemplate jdbc;
    private static EmailDeliveryRepository ledgerRepository;
    private static EmailDeliveryService ledger;
    private static MailEligibilityRepository eligibility;
    private static MailViewBuilder views;
    private static ScheduledThreadPoolExecutor watchdog;
    private static Semaphore slot;
    private static long userId;

    private CaptureSmtpServer capture;
    private OrderEvent event;
    private EmailTransportPause pause;
    private MessageListenerContainer emailContainer;
    private RabbitListenerEndpointRegistry listeners;
    private RabbitRetryPublisher retryPublisher;
    private com.rabbitmq.client.Channel channel;
    private List<String> trace;
    private List<Map<String, Object>> quarantines;
    private EmailNotificationConsumer consumer;

    @BeforeAll
    static void setUp() throws Exception {
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
            config.setPoolName("t11-email-consumer");
            dataSource = new HikariDataSource(config);
            jdbc = new NamedParameterJdbcTemplate(dataSource);
            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            ledgerRepository = new EmailDeliveryRepository(jdbc);
            ledger = new EmailDeliveryService(ledgerRepository, new OrderNotificationPolicy(),
                    transactionManager);
            eligibility = new MailEligibilityRepository(jdbc);
            views = new MailViewBuilder(new MailContentProperties(
                    "http://localhost:3000", "no-reply@notifications.local", "mail.example.test"));
            watchdog = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "t11-consumer-watchdog");
                t.setDaemon(true);
                return t;
            });
            slot = new Semaphore(1);
            seed();
            userId = scalarLong("select id from users where user_id = 'USR_t11_cust'");
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
            System.out.println("Confirmed task11 scratch cleanup: " + name);
            database = null;
        }
    }

    @BeforeEach
    void reset() throws Exception {
        execute("delete from email_deliveries");
        execute("delete from notifications where notification_id like 'NOTI_t11_%'");
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

        // The SENT write fails after SMTP acceptance; the real claim/ledger remains authoritative.
        EmailDeliveryService failingLedger = new EmailDeliveryService(
                ledgerRepository, new OrderNotificationPolicy(),
                new DataSourceTransactionManager(dataSource)) {
            @Override
            public boolean completeSent(Claim claim) {
                throw new IllegalStateException("injected outcome persistence failure");
            }
        };
        OrderEmailService testService = new OrderEmailService(failingLedger, eligibility,
                new OrderNotificationPolicy(),
                new SmtpAttemptExecutor(new SmtpAttemptExecutor.Target(
                        "127.0.0.1", capture.port(), null, null, false), watchdog, slot),
                views, new MailContentProperties(), new DataSourceTransactionManager(dataSource));

        DatabaseClock clock = mock(DatabaseClock.class);
        when(clock.now()).thenReturn(NOW);
        retryPublisher = mock(RabbitRetryPublisher.class);
        NotificationConsumerSupport support = new NotificationConsumerSupport(
                retryPublisher, new OrderEventAgeGuard(clock));
        OrderEventCodec codec = mock(OrderEventCodec.class);
        event = event();
        when(codec.decode(any())).thenReturn(event);
        pause = new EmailTransportPause();
        listeners = mock(RabbitListenerEndpointRegistry.class);
        emailContainer = mock(MessageListenerContainer.class);
        when(listeners.getListenerContainer(EmailNotificationConsumer.LISTENER_ID))
                .thenReturn(emailContainer);
        channel = mock(com.rabbitmq.client.Channel.class);
        trace = new ArrayList<>();
        quarantines = new ArrayList<>();
        doAnswer(invocation -> {
            trace.add("retry:" + invocation.getArgument(3));
            return null;
        }).when(retryPublisher).publishRetry(any(), any(), any(), anyInt());
        doAnswer(invocation -> {
            trace.add("dlq:" + invocation.getArgument(2));
            Map<String, Object> headers = invocation.getArgument(3);
            quarantines.add(new java.util.HashMap<>(headers));
            return null;
        }).when(retryPublisher).publishDeadLetter(any(), any(), anyString(), anyMap());
        doAnswer(invocation -> {
            trace.add("ack:" + invocation.getArgument(0));
            return null;
        }).when(channel).basicAck(anyLong(), anyBoolean());
        consumer = new EmailNotificationConsumer(codec, support, testService, ledger,
                pause, listeners);
    }

    @Test
    void outcomePersistenceFailureRedeliveryNeverSendsAgainAndNeverAcksAwayWork() throws Exception {
        // 1) SMTP accepts, but the SENT ledger write fails. Work stays unresolved.
        consumer.receive(message(1L, 0), channel);

        assertThat(capture.connectionCount()).isEqualTo(1);
        assertThat(capture.messageReceived()).isTrue();
        assertThat(row(event.eventId()).state()).isEqualTo("IN_FLIGHT");
        assertThat(trace).containsExactly("retry:1", "ack:1");

        // 2) Broker redelivery while the claim is active: Busy, no second SMTP.
        trace.clear();
        consumer.receive(message(2L, 1), channel);

        assertThat(capture.connectionCount()).isEqualTo(1);
        assertThat(row(event.eventId()).state()).isEqualTo("IN_FLIGHT");
        assertThat(trace).containsExactly("retry:2", "ack:2");

        // 3) Lease expiry turns the unresolved claim into UNKNOWN quarantine evidence.
        execute("update email_deliveries set claim_expires_at = "
                + "clock_timestamp() - interval '1 second' where event_id = '"
                + event.eventId() + "'");
        trace.clear();
        consumer.receive(message(3L, 2), channel);

        assertThat(capture.connectionCount()).isEqualTo(1);
        Row quarantined = row(event.eventId());
        assertThat(quarantined.state()).isEqualTo("UNKNOWN");
        assertThat(quarantined.failureCode()).isEqualTo("LEASE_EXPIRED_UNKNOWN");
        assertThat(trace).containsExactly("dlq:EMAIL_UNKNOWN", "ack:3");
        assertThat(quarantines).singleElement().satisfies(headers -> {
            assertThat(headers.get("x-delivery-id")).isEqualTo(quarantined.id());
            assertThat(headers.get("x-safe-code")).isEqualTo("LEASE_EXPIRED_UNKNOWN");
        });
        assertThat(pause.isPaused()).isTrue();
        verify(emailContainer).stop();
        verify(listeners, org.mockito.Mockito.never())
                .getListenerContainer("order-notifications-inapp");

        // 4) A post-pause redelivery never starts a replacement SMTP attempt.
        trace.clear();
        consumer.receive(message(4L, 3), channel);

        assertThat(capture.connectionCount()).isEqualTo(1);
        assertThat(trace).containsExactly("dlq:EMAIL_PAUSED", "ack:4");
        System.out.println("TASK11_EVIDENCE outcomePersistenceFailure smtpConnections="
                + capture.connectionCount() + " finalState=" + row(event.eventId()).state()
                + " trace=" + trace + " paused=" + pause.isPaused());
    }

    private record Row(long id, String state, String failureCode) {
    }

    private static Row row(UUID eventId) {
        List<Row> rows = jdbc.query("""
                select id, state, failure_code from email_deliveries where event_id = :eventId
                """, new MapSqlParameterSource("eventId", eventId), (result, index) -> new Row(
                result.getLong("id"), result.getString("state"), result.getString("failure_code")));
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static OrderEvent event() {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, ORDER_ID, 1L,
                NOW.minusSeconds(30), userId, OrderStatus.PENDING, PaymentStatus.PENDING,
                PaymentMethod.VIETQR, new BigDecimal("10.00"), null, "t11", "TEST");
    }

    private Message message(long deliveryTag, int attempt) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setMessageId(event.eventId().toString());
        properties.setHeader(RabbitRetryPublisher.RETRY_ATTEMPT_HEADER, attempt);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
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
        System.out.println("Verified task11 scratch identity: " + scratchDatabaseName);
    }

    private static void seed() {
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select 'USR_t11_cust', 't11_cust@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'customer'");
        execute("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                + "select 'CUS_t11_cust', id, 'Fixture', '000' "
                + "from users where user_id = 'USR_t11_cust'");
        execute("insert into orders (order_id, customer_id, order_status, order_total, "
                + "order_address, order_recipient_name, order_phone, payment_expires_at) "
                + "select '" + ORDER_ID + "', id, 'PENDING', 10, 'fixture', 'Fixture', '000', "
                + "clock_timestamp() + interval '10 minutes' "
                + "from customers where customer_id = 'CUS_t11_cust'");
        execute("insert into payments (payment_id, order_id, payment_method, "
                + "payment_status, payment_amount) "
                + "select 'PAY_t11', id, 'VIETQR', 'PENDING', 10 "
                + "from orders where order_id = '" + ORDER_ID + "'");
    }
}
