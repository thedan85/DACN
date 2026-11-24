package com.project.dacn.messaging.outbox;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.support.ExternalPostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
class OrderEventOutboxPostgresTest {

    private static ExternalPostgresDatabase database;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (database == null) {
            database = ExternalPostgresDatabase.create();
            database.migrate();
        }
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
    }

    @AfterAll
    static void dropScratchDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OrderEventOutbox outbox;

    @Autowired
    OrderEventCodec codec;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUpTransactions() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void appendRequiresAnExistingTransaction() {
        OrderFixture fixture = insertOrder();
        OrderEvent event = event(fixture, 1L);

        assertThatThrownBy(() -> outbox.append(fixture.orderPk(), event))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(countByEventId(event.eventId())).isZero();
    }

    @Test
    void appendRollsBackWhenTheCallerTransactionFails() {
        OrderFixture fixture = insertOrder();
        OrderEvent event = event(fixture, 1L);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            outbox.append(fixture.orderPk(), event);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(countByEventId(event.eventId())).isZero();
    }

    @Test
    void appendStoresTheEncodedEventForTheOrderAndCommits() throws Exception {
        OrderFixture fixture = insertOrder();
        OrderEvent event = event(fixture, 1L);

        transactions.executeWithoutResult(status -> outbox.append(fixture.orderPk(), event));

        assertThat(countByEventId(event.eventId())).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("""
                select event_id, order_id, order_sequence, event_type, schema_version,
                       payload::text as payload, available_at, attempt_count, lease_owner,
                       lease_token, lease_expires_at, published_at, quarantined_at,
                       last_error_code
                  from event_outbox
                 where event_id = ?
                """, event.eventId());

        assertThat(row.get("event_id")).isEqualTo(event.eventId());
        assertThat(((Number) row.get("order_id")).longValue()).isEqualTo(fixture.orderPk());
        assertThat(((Number) row.get("order_sequence")).longValue())
                .isEqualTo(event.orderSequence());
        assertThat(row.get("event_type")).isEqualTo(event.eventType().name());
        assertThat(((Number) row.get("schema_version")).intValue())
                .isEqualTo(event.schemaVersion());
        assertThat(mapper.readTree((String) row.get("payload")))
                .isEqualTo(mapper.readTree(codec.encode(event)));
        assertThat(((Timestamp) row.get("available_at")).toInstant())
                .isEqualTo(event.occurredAt());
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
        assertThat(row.get("lease_owner")).isNull();
        assertThat(row.get("lease_token")).isNull();
        assertThat(row.get("lease_expires_at")).isNull();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("quarantined_at")).isNull();
        assertThat(row.get("last_error_code")).isNull();
    }

    @Test
    void duplicateOrderSequenceForTheSameOrderIsRejected() {
        OrderFixture fixture = insertOrder();
        OrderEvent first = event(fixture, 1L);
        appendAndCommit(fixture.orderPk(), first);
        OrderEvent duplicateSequence = event(fixture, 1L);

        assertThatThrownBy(() -> appendAndCommit(fixture.orderPk(), duplicateSequence))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countByEventId(duplicateSequence.eventId())).isZero();
        assertThat(countByOrder(fixture.orderPk())).isEqualTo(1);
    }

    @Test
    void duplicateEventIdWithADifferentOrderSequenceIsRejected() {
        OrderFixture fixture = insertOrder();
        OrderEvent first = event(fixture, 1L);
        appendAndCommit(fixture.orderPk(), first);
        OrderEvent duplicateId = new OrderEvent(first.schemaVersion(), first.eventId(),
                first.eventType(), first.orderId(), first.orderSequence() + 1,
                first.occurredAt(), first.customerUserId(), first.orderStatus(),
                first.paymentStatus(), first.paymentMethod(), first.total(), first.reason(),
                first.correlationId(), first.entryPoint());

        assertThatThrownBy(() -> appendAndCommit(fixture.orderPk(), duplicateId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countByEventId(duplicateId.eventId())).isEqualTo(1);
        assertThat(countByOrder(fixture.orderPk())).isEqualTo(1);
    }

    private void appendAndCommit(long orderPk, OrderEvent event) {
        transactions.executeWithoutResult(status -> outbox.append(orderPk, event));
    }

    private long countByEventId(UUID eventId) {
        return jdbc.queryForObject(
                "select count(*) from event_outbox where event_id = ?", Long.class, eventId);
    }

    private long countByOrder(long orderPk) {
        return jdbc.queryForObject(
                "select count(*) from event_outbox where order_id = ?", Long.class, orderPk);
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

    private static OrderEvent event(OrderFixture fixture, long orderSequence) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, fixture.orderId(), orderSequence,
                Instant.parse("2026-11-03T10:15:30.123456Z"), fixture.userId(),
                OrderStatus.PENDING, PaymentStatus.PENDING, PaymentMethod.COD,
                new BigDecimal("199.90"), null, "corr-" + UUID.randomUUID(), "CHECKOUT_WEB");
    }

    private record OrderFixture(long orderPk, long userId, String orderId) {
    }
}
