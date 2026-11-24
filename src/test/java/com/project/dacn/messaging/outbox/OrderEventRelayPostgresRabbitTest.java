package com.project.dacn.messaging.outbox;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.rabbit.NotificationRabbitProperties;
import com.project.dacn.messaging.rabbit.RabbitEventPublisher;
import com.project.dacn.messaging.rabbit.RabbitDeliveryStartup;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import com.project.dacn.messaging.rabbit.RabbitTopologyConfig;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.project.dacn.support.ExternalRabbit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("external-rabbit-dacn-topology")
class OrderEventRelayPostgresRabbitTest {

    private static ExternalPostgresDatabase database;
    private static CachingConnectionFactory connectionFactory;
    private static RabbitAdmin admin;
    private static RabbitTemplate template;
    private static Collection<Declarable> declarations;
    private static ExternalRabbit.OwnedTopology ownedTopology;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static DatabaseClock clock;
    private static OrderEventOutbox outbox;
    private static OrderEventRelay relay;

    @BeforeAll
    static void setUp() {
        database = ExternalPostgresDatabase.create();
        database.migrate();

        DataSource dataSource = new DriverManagerDataSource(
                database.jdbcUrl(), database.username(), database.password());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        connectionFactory = ExternalRabbit.fromEnvironment().connectionFactory();
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        admin = new RabbitAdmin(connectionFactory);
        declarations = new RabbitTopologyConfig().notificationTopology().getDeclarables();
        ownedTopology = ExternalRabbit.declare(admin, declarations);

        OrderEventCodec codec = new OrderEventCodec(JsonMapper.builder().build());
        clock = new DatabaseClock(jdbc);
        NotificationRabbitProperties properties = new NotificationRabbitProperties();
        properties.setConfirmTimeout(Duration.ofSeconds(5));
        RabbitEventPublisher publisher = new RabbitEventPublisher(template, codec, properties);

        outbox = new OrderEventOutbox(jdbc, codec);
        RabbitDeliveryStartup deliveryStartup = mock(RabbitDeliveryStartup.class);
        when(deliveryStartup.isReady()).thenReturn(true);
        relay = new OrderEventRelay(new OutboxRelayRepository(jdbc), codec, clock,
                new OrderEventAgeGuard(clock), publisher,
                new OutboxTelemetry(new SimpleMeterRegistry(), jdbc), deliveryStartup);
    }

    @BeforeEach
    void resetState() {
        jdbc.update("delete from event_outbox");
        ownedTopology.purgeQueues();
    }

    @AfterAll
    static void tearDown() {
        if (ownedTopology != null) {
            ownedTopology.close();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void pollPublishesACommittedRowToBothQueuesAndMarksItPublished() {
        OrderFixture fixture = insertOrder();
        OrderEvent event = event(fixture, 1L, clock.now().minusSeconds(60));
        transactions.executeWithoutResult(status -> outbox.append(fixture.orderPk(), event));

        relay.poll();

        Message inapp = template.receive(RabbitTopology.INAPP_QUEUE, 5_000);
        Message email = template.receive(RabbitTopology.EMAIL_QUEUE, 5_000);

        assertThat(inapp).isNotNull();
        assertThat(email).isNotNull();
        assertThat(inapp.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        assertThat(email.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());

        Map<String, Object> row = row(event.eventId());
        assertThat(row.get("published_at")).isNotNull();
        assertThat(row.get("quarantined_at")).isNull();
        assertThat(row.get("last_error_code")).isNull();
    }

    @Test
    void pollQuarantinesAnOverAgeRowAndNeverPublishesIt() {
        OrderFixture fixture = insertOrder();
        OrderEvent event = event(fixture, 1L, clock.now().minus(31, ChronoUnit.DAYS));
        transactions.executeWithoutResult(status -> outbox.append(fixture.orderPk(), event));

        relay.poll();

        Map<String, Object> row = row(event.eventId());
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("quarantined_at")).isNotNull();
        assertThat(row.get("last_error_code")).isEqualTo("EVENT_TOO_OLD");

        assertThat(template.receive(RabbitTopology.INAPP_QUEUE, 250)).isNull();
        assertThat(template.receive(RabbitTopology.EMAIL_QUEUE, 250)).isNull();
    }

    private Map<String, Object> row(UUID eventId) {
        return jdbc.queryForMap("""
                select published_at, quarantined_at, last_error_code
                  from event_outbox
                 where event_id = ?
                """, eventId);
    }

    private OrderFixture insertOrder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long roleId = jdbc.queryForObject("""
                insert into roles (role_id, role_code, role_name)
                values (?, ?, ?)
                returning id
                """, Long.class, "test-role-" + suffix, "test_role_" + suffix, "Test Role");
        long userId = jdbc.queryForObject("""
                insert into users (user_id, user_email, user_password_hash, role_id)
                values (?, ?, ?, ?)
                returning id
                """, Long.class, "test-user-" + suffix, suffix + "@example.com", "hash", roleId);
        long customerId = jdbc.queryForObject("""
                insert into customers (customer_id, user_id, customer_name, customer_phone)
                values (?, ?, ?, ?)
                returning id
                """, Long.class, "test-customer-" + suffix, userId, "Test Customer",
                "0900000000");
        String orderId = "TEST_ORDER_" + suffix;
        long orderPk = jdbc.queryForObject("""
                insert into orders
                    (order_id, customer_id, order_total, order_address, order_recipient_name,
                     order_phone)
                values (?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, orderId, customerId, new BigDecimal("199.90"), "Test Address",
                "Test Recipient", "0900000000");
        return new OrderFixture(orderPk, userId, orderId);
    }

    private static OrderEvent event(OrderFixture fixture, long orderSequence, Instant occurredAt) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, fixture.orderId(), orderSequence,
                occurredAt, fixture.userId(), OrderStatus.PENDING, PaymentStatus.PENDING,
                PaymentMethod.COD, new BigDecimal("199.90"), null,
                "corr-" + UUID.randomUUID(), "CHECKOUT_WEB");
    }

    private record OrderFixture(long orderPk, long userId, String orderId) {
    }
}
