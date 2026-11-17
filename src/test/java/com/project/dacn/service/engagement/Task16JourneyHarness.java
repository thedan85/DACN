package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.integration.mail.MailContentProperties;
import com.project.dacn.integration.mail.SmtpAttemptExecutor;
import com.project.dacn.messaging.OrderEventPublisher;
import com.project.dacn.messaging.rabbit.NotificationRabbitProperties;
import com.project.dacn.messaging.rabbit.RabbitDeliveryStartup;
import com.project.dacn.messaging.rabbit.RabbitEventPublisher;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import com.project.dacn.messaging.rabbit.RabbitTopologyConfig;
import com.project.dacn.messaging.rabbit.consumer.EmailNotificationConsumer;
import com.project.dacn.messaging.rabbit.consumer.EmailTransportPause;
import com.project.dacn.messaging.rabbit.consumer.InAppNotificationConsumer;
import com.project.dacn.messaging.rabbit.consumer.NotificationChannel;
import com.project.dacn.messaging.rabbit.consumer.NotificationConsumerSupport;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import com.project.dacn.messaging.rabbit.consumer.RabbitRetryPublisher;
import com.project.dacn.messaging.outbox.OutboxRelayRepository;
import com.project.dacn.messaging.outbox.OutboxTelemetry;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.messaging.outbox.OrderEventOutbox;
import com.project.dacn.messaging.outbox.OrderEventRelay;
import com.project.dacn.repository.engagement.EmailDeliveryRepository;
import com.project.dacn.repository.engagement.MailEligibilityRepository;
import com.project.dacn.repository.engagement.NotificationFanoutRepository;
import com.project.dacn.repository.engagement.NotificationRecipientQueries;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.project.dacn.support.ExternalRabbit;
import com.project.dacn.support.MutationTestSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.project.dacn.event.OrderEventType.ORDER_PLACED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invocation-owned PostgreSQL + RabbitMQ + loopback SMTP harness for the Task 16
 * journey. Every external resource is created by this invocation, identity is
 * verified before/after, and all resources are removed on close. Credentials
 * live only in process environment variables and are never logged.
 */
public final class Task16JourneyHarness implements AutoCloseable {

    public static final String CUSTOMER = "USR_t16_cust";
    public static final String ADMIN = "USR_t16_admin";
    public static final String EMPLOYEE = "USR_t16_emp";

    public final ExternalPostgresDatabase database;
    public final String scratchDatabaseName;
    public final HikariDataSource dataSource;
    public final JdbcTemplate jdbc;
    public final NamedParameterJdbcTemplate named;
    public final TransactionTemplate tx;

    /** Real DatabaseClock with a controllable time source for age-cutover proof. */
    public final MutableClock clock;
    public final OrderEventCodec codec;
    public final OrderEventAgeGuard ageGuard;
    public final OrderEventOutbox outbox;
    public final OutboxRelayRepository relayRepository;

    public final long customerUserId;
    public final long adminUserId;
    public final long employeeUserId;
    public final long customerPk;
    public final long adminPk;
    public final long employeePk;

    public final CachingConnectionFactory connectionFactory;
    public final RabbitAdmin rabbitAdmin;
    public final RabbitTemplate rabbitTemplate;
    private com.rabbitmq.client.Connection brokerConnection;
    private com.rabbitmq.client.Channel publishChannel;
    private com.rabbitmq.client.Channel consumeChannel;
    public final ExternalRabbit.OwnedTopology ownedTopology;
    public final NotificationRabbitProperties rabbitProperties;
    public final RabbitRetryPublisher retryPublisher;
    public final RabbitEventPublisher realPublisher;
    public final FaultyPublisher publisher;
    public final OrderEventRelay relay;

    public final NotificationFanoutService fanout;
    public final InAppNotificationConsumer inAppConsumer;
    public final MutationTestSupport mutation;
    public final NotificationRetentionPruner pruner;

    public final ScheduledThreadPoolExecutor watchdog;
    public final Semaphore smtpSlot;

    public CaptureSmtpServer capture;
    public EmailDeliveryService delivery;
    public OrderEmailService emailService;
    public EmailNotificationConsumer emailConsumer;

    public final EmailTransportPause pause = new EmailTransportPause();
    public final Instant dbNow;

    private Task16JourneyHarness() throws Exception {
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
        config.setMaximumPoolSize(10);
        config.setPoolName("t16-journey-harness");
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
        assertThat(jdbcVersion()).as("migrated through V38").isEqualTo("38");
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        clock = new MutableClock(jdbc);
        dbNow = clock.now();

        codec = new OrderEventCodec(JsonMapper.builder().build());
        ageGuard = new OrderEventAgeGuard(clock);
        outbox = new OrderEventOutbox(jdbc, codec);
        relayRepository = new OutboxRelayRepository(jdbc);

        connectionFactory = ExternalRabbit.fromEnvironment().connectionFactory();
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        rabbitAdmin = new RabbitAdmin(connectionFactory);
        java.util.Collection<Declarable> declarations =
                new RabbitTopologyConfig().notificationTopology().getDeclarables();
        ownedTopology = ExternalRabbit.declare(rabbitAdmin, declarations);
        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        brokerConnection = connectionFactory.getRabbitConnectionFactory().newConnection("t16-journey");
        publishChannel = brokerConnection.createChannel();
        consumeChannel = brokerConnection.createChannel();

        rabbitProperties = new NotificationRabbitProperties();
        rabbitProperties.setConfirmTimeout(Duration.ofSeconds(5));
        retryPublisher = new RabbitRetryPublisher(rabbitTemplate, rabbitProperties);
        realPublisher = new RabbitEventPublisher(rabbitTemplate, codec, rabbitProperties);
        publisher = new FaultyPublisher(realPublisher);
        relay = new OrderEventRelay(relayRepository, codec, clock, ageGuard, publisher,
                new OutboxTelemetry(new SimpleMeterRegistry(), jdbc), readyStartup());

        NotificationFanoutRepository fanoutRepo = new NotificationFanoutRepository(named);
        NotificationRecipientQueries recipients = new NotificationRecipientQueries(named);
        fanout = new NotificationFanoutService(fanoutRepo, recipients, clock, ageGuard,
                new NotificationMessageRenderer(), new OrderNotificationPolicy());
        inAppConsumer = new InAppNotificationConsumer(codec,
                new NotificationConsumerSupport(retryPublisher, ageGuard), fanout);
        mutation = MutationTestSupport.create(dataSource);
        pruner = new NotificationRetentionPruner(jdbc,
                new NotificationRetentionTelemetry(new SimpleMeterRegistry(), jdbc));

        watchdog = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "t16-watchdog");
            t.setDaemon(true);
            return t;
        });
        smtpSlot = new Semaphore(1);
        seed();
        customerUserId = scalarLong("select id from users where user_id = '" + CUSTOMER + "'");
        adminUserId = scalarLong("select id from users where user_id = '" + ADMIN + "'");
        employeeUserId = scalarLong("select id from users where user_id = '" + EMPLOYEE + "'");
        customerPk = customerUserId;
        adminPk = adminUserId;
        employeePk = employeeUserId;
        resetCapture();
        System.out.println("TASK16_HARNESS_READY scratch=" + scratchDatabaseName
                + " migratedThrough=V38 rabbitTopologyOwned=true");
    }

    public static Task16JourneyHarness create() throws Exception {
        return new Task16JourneyHarness();
    }

    /** Rebuild the loopback SMTP peer and the real email components that depend on it. */
    public final void resetCapture() throws java.io.IOException {
        if (capture != null) {
            capture.close();
        }
        capture = new CaptureSmtpServer();
        delivery = new EmailDeliveryService(new EmailDeliveryRepository(named),
                new OrderNotificationPolicy(), new DataSourceTransactionManager(dataSource));
        MailViewBuilder views = new MailViewBuilder(new MailContentProperties(
                "http://localhost:3000", "no-reply@notifications.local", "mail.example.test"));
        emailService = new OrderEmailService(delivery, new MailEligibilityRepository(named),
                new OrderNotificationPolicy(),
                new SmtpAttemptExecutor(new SmtpAttemptExecutor.Target(
                        "127.0.0.1", capture.port(), null, null, false), watchdog, smtpSlot),
                views, new MailContentProperties(), new DataSourceTransactionManager(dataSource));
        emailConsumer = new EmailNotificationConsumer(codec,
                new NotificationConsumerSupport(retryPublisher, ageGuard), emailService,
                delivery, pause, new RabbitListenerEndpointRegistry());
    }

    /** Make the SMTP peer unavailable without a mock: close its listening socket. */
    public final void stopCapture() {
        if (capture != null) {
            capture.close();
        }
    }

    public final boolean deliverInapp() throws Exception {
        return deliver(RabbitTopology.INAPP_QUEUE,
                (message, channel) -> inAppConsumer.receive(message, channel));
    }

    public final boolean deliverEmail() throws Exception {
        return deliver(RabbitTopology.EMAIL_QUEUE,
                (message, channel) -> emailConsumer.receive(message, channel));
    }

    public final boolean deliver(String queue, ChannelAwareMessageListener listener) throws Exception {
        // Quorum-queue visibility can lag a publish briefly; poll a bounded budget.
        for (int attempt = 0; attempt < 20; attempt++) {
            com.rabbitmq.client.GetResponse response = consumeChannel.basicGet(queue, false);
            if (response != null) {
                MessageProperties props = properties(response);
                props.setDeliveryTag(response.getEnvelope().getDeliveryTag());
                listener.onMessage(new Message(response.getBody(), props), consumeChannel);
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    public final void publishToQueue(String queue, OrderEvent event, int attempt, String reason)
            throws Exception {
        Map<String, Object> headers = new HashMap<>();
        headers.put(RabbitRetryPublisher.RETRY_ATTEMPT_HEADER, attempt);
        if (reason != null) {
            headers.put(RabbitRetryPublisher.QUARANTINE_REASON_HEADER, reason);
        }
        com.rabbitmq.client.AMQP.BasicProperties props =
                new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                        .messageId(event.eventId().toString())
                        .correlationId(event.correlationId())
                        .timestamp(Date.from(event.occurredAt()))
                        .contentType(MessageProperties.CONTENT_TYPE_JSON)
                        .deliveryMode(2) // PERSISTENT
                        .headers(headers)
                        .build();
        publishChannel.basicPublish("", queue, false, props, codec.encode(event));
    }

    public final Message peek(String queue) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            com.rabbitmq.client.GetResponse response = consumeChannel.basicGet(queue, false);
            if (response != null) {
                MessageProperties props = properties(response);
                consumeChannel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
                return new Message(response.getBody(), props);
            }
            Thread.sleep(100);
        }
        return null;
    }

    /**
     * Manual DLQ replay: republish the exact bytes and properties of an existing
     * message (typically read with {@link #peek(String)}) to a target queue.
     * No event is rebuilt or re-encoded, so body, headers, message id, timestamp
     * and the embedded occurredAt are preserved byte-for-byte.
     */
    public final void publishMessage(String queue, Message message) throws Exception {
        MessageProperties source = message.getMessageProperties();
        Map<String, Object> headers = source.getHeaders() == null
                ? new HashMap<>() : new HashMap<>(source.getHeaders());
        int deliveryMode = source.getDeliveryMode() == MessageDeliveryMode.NON_PERSISTENT ? 1 : 2;
        com.rabbitmq.client.AMQP.BasicProperties props =
                new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                        .messageId(source.getMessageId())
                        .correlationId(source.getCorrelationId())
                        .timestamp(source.getTimestamp())
                        .contentType(source.getContentType())
                        .contentEncoding(source.getContentEncoding())
                        .deliveryMode(deliveryMode)
                        .headers(headers)
                        .build();
        publishChannel.basicPublish("", queue, false, props, message.getBody());
    }

    public final long count(String queue) throws Exception {
        return consumeChannel.queueDeclarePassive(queue).getMessageCount();
    }

    /** Place + persist an order/payment and append the fact inside ONE transaction. */
    public final OrderEvent placeOrderWithOutbox(String orderId, PaymentMethod method) {
        OrderEvent[] ref = new OrderEvent[1];
        tx.executeWithoutResult(status -> {
            jdbc.update("insert into orders (order_id, customer_id, order_status, order_total, "
                            + "order_address, order_recipient_name, order_phone) "
                            + "select ?, id, 'PENDING', 10, 'fixture', 'Fixture', '000' "
                            + "from customers where customer_id = 'CUS_t16'", orderId);
            long orderPk = jdbc.queryForObject("select id from orders where order_id = ?",
                    Long.class, orderId);
            jdbc.update("insert into payments (payment_id, order_id, payment_method, "
                            + "payment_status, payment_amount) values (?, ?, ?, 'PENDING', 10)",
                    "PAY_" + orderId, orderPk, method.name());
            ref[0] = OrderEvent.create(ORDER_PLACED, orderId, 1L, clock.now().minusSeconds(60),
                    customerUserId, OrderStatus.PENDING, PaymentStatus.PENDING, method,
                    new BigDecimal("10.00"), null, "t16-" + orderId, "CHECKOUT_WEB");
            outbox.append(orderPk, ref[0]);
        });
        return ref[0];
    }

    public final void execute(String sql) {
        jdbc.execute(sql);
    }

    public final long scalarLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        assertThat(value).as("scalar %s", sql).isNotNull();
        return value;
    }

    public final List<String> strings(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    public final List<Long> longs(String sql) {
        return jdbc.queryForList(sql, Long.class);
    }

    public final String outboxRow(UUID eventId, String column) {
        return jdbc.queryForObject("select " + column + "::text from event_outbox where event_id = ?",
                String.class, eventId);
    }

    /** Decode the raw captured RFC-822 transfer into readable plain-text bodies. */
    public final String capturedPlainText() {
        String raw = capture.lastRawMessage();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            jakarta.mail.Session session = jakarta.mail.Session.getInstance(new java.util.Properties());
            jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(
                    session, new java.io.ByteArrayInputStream(
                            raw.replaceAll("(?m)^\\.\\.", ".").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Object content = message.getContent();
            StringBuilder text = new StringBuilder();
            appendText(content, text);
            return text.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode captured SMTP message", e);
        }
    }

    private static void appendText(Object content, StringBuilder out) throws Exception {
        if (content instanceof String s) {
            out.append(s);
        } else if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                jakarta.mail.BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("text/plain")) {
                    appendText(part.getContent(), out);
                }
            }
        }
    }

    private static MessageProperties properties(com.rabbitmq.client.GetResponse response) {
        com.rabbitmq.client.AMQP.BasicProperties p = response.getProps();
        MessageProperties props = new MessageProperties();
        if (p == null) {
            return props;
        }
        props.setMessageId(p.getMessageId());
        props.setCorrelationId(p.getCorrelationId());
        props.setContentType(p.getContentType());
        props.setContentEncoding(p.getContentEncoding());
        if (p.getTimestamp() != null) {
            props.setTimestamp(p.getTimestamp());
        }
        if (p.getDeliveryMode() != null) {
            props.setDeliveryMode(MessageDeliveryMode.fromInt(p.getDeliveryMode()));
        }
        if (p.getHeaders() != null) {
            props.setHeaders(new HashMap<>(p.getHeaders()));
        }
        return props;
    }

    private static RabbitDeliveryStartup readyStartup() {
        // Deployment readiness is not a journey behaviour. A real subclass that
        // reports ready avoids a mock; no SMTP or consumer logic is stubbed.
        return new RabbitDeliveryStartup(null, null, null) {
            @Override
            public boolean isReady() {
                return true;
            }
        };
    }

    private String jdbcVersion() {
        String version = jdbc.queryForObject(
                "select version from flyway_schema_history where version = '38' and success",
                String.class);
        return version;
    }

    private void seed() {
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select '" + CUSTOMER + "', 't16_cust@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'customer'");
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select '" + ADMIN + "', 't16_admin@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'admin'");
        execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select '" + EMPLOYEE + "', 't16_emp@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'employee'");
        execute("insert into admins (admin_id, user_id, admin_name, admin_status) "
                + "select 'ADM_t16', id, 'Fixture', 'ACTIVE' from users where user_id = '" + ADMIN + "'");
        execute("insert into employees (employee_id, user_id, employee_name, employee_status) "
                + "select 'EMP_t16', id, 'Fixture', 'ACTIVE' from users where user_id = '" + EMPLOYEE + "'");
        execute("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                + "select 'CUS_t16', id, 'Fixture', '000' from users where user_id = '" + CUSTOMER + "'");
        // Admin already holds order:read from the migration seed, so COMPANY
        // inbox visibility needs no extra permission insert here.
    }

    private void verifyIdentity() {
        assertThat(scratchDatabaseName).matches("verify_event_foundation_[0-9a-f]{12}");
        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
             PreparedStatement statement = connection.prepareStatement("select current_database()");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(scratchDatabaseName);
        } catch (SQLException e) {
            throw new IllegalStateException("scratch identity check failed", e);
        }
    }

    @Override
    public void close() throws Exception {
        if (capture != null) {
            capture.close();
        }
        if (publishChannel != null) {
            try { publishChannel.close(); } catch (Exception ignored) { }
        }
        if (consumeChannel != null) {
            try { consumeChannel.close(); } catch (Exception ignored) { }
        }
        if (brokerConnection != null) {
            try { brokerConnection.close(); } catch (Exception ignored) { }
        }
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        if (ownedTopology != null) {
            ownedTopology.close(); // deletes queues, then exchanges (binding-safe order)
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (mutation != null) {
            mutation.close();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        database.drop();
        try (Connection admin = DriverManager.getConnection(
                System.getenv("TEST_POSTGRES_URL"),
                System.getenv("TEST_POSTGRES_USERNAME"),
                System.getenv("TEST_POSTGRES_PASSWORD"));
             PreparedStatement statement = admin.prepareStatement(
                     "select 1 from pg_database where datname = ?")) {
            statement.setString(1, scratchDatabaseName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("exact invocation-owned database must be gone").isFalse();
            }
        }
        System.out.println("TASK16_CLEANUP scratch=" + scratchDatabaseName + " remaining=0"
                + " brokerTopologyRemoved=true");
    }

    /** Controllable real clock; null override means live database time. */
    public static final class MutableClock extends DatabaseClock {
        private volatile Instant override;

        public MutableClock(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public Instant now() {
            Instant value = override;
            return value != null ? value : super.now();
        }

        public void set(Instant value) {
            this.override = value;
        }

        public void clear() {
            this.override = null;
        }
    }

    /** Delegates to the real publisher; a single publish can be forced to fail. */
    public static final class FaultyPublisher implements OrderEventPublisher {
        private final OrderEventPublisher delegate;
        private final AtomicBoolean failNext = new AtomicBoolean(false);

        public FaultyPublisher(OrderEventPublisher delegate) {
            this.delegate = delegate;
        }

        @Override
        public void publish(OrderEvent event) {
            if (failNext.compareAndSet(true, false)) {
                throw new com.project.dacn.messaging.EventPublishException(
                        com.project.dacn.messaging.EventPublishException.ReasonCode.BROKER_UNAVAILABLE);
            }
            delegate.publish(event);
        }

        public void failNext() {
            failNext.set(true);
        }
    }
}
