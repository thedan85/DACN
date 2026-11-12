package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.dto.request.order.StaffOrderStatusUpdateRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.messaging.outbox.OrderEventOutbox;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.order.OrderLifecycleService;
import com.project.dacn.service.order.OrderResponseAssembler;
import com.project.dacn.service.order.OrderService;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real staff/admin lifecycle transitions against PostgreSQL locks, stock and the outbox. */
class OrderLifecyclePostgresTest {

    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static HikariDataSource pool;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static OrderService orderService;
    private static VariantRepository variantRepository;
    private static ObjectMapper mapper;

    @BeforeAll
    static void startOnlyOwnedScratch() throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate"))
                .setLevel(ch.qos.logback.classic.Level.ERROR);
        database = ExternalPostgresDatabase.create();
        ownedName = nameOf(database);
        try {
            verifyIdentity(database);
            database.migrate();
            HikariConfig hikari = new HikariConfig();
            hikari.setJdbcUrl(database.jdbcUrl());
            hikari.setUsername(database.username());
            hikari.setPassword(database.password());
            hikari.setMaximumPoolSize(5);
            hikari.setMinimumIdle(1);
            hikari.setPoolName("task5-lifecycle-pg");
            pool = new HikariDataSource(hikari);
            DataSource source = pool;
            context = new AnnotationConfigApplicationContext();
            context.registerBean(DataSource.class, () -> source);
            context.register(Config.class);
            context.refresh();
            jdbc = context.getBean(JdbcTemplate.class);
            mapper = context.getBean(ObjectMapper.class);
            transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            orderService = context.getBean(OrderService.class);
            variantRepository = context.getBean(VariantRepository.class);
            assertThat(jdbc.queryForObject("""
                    select count(*) from flyway_schema_history
                     where version = '34' and success
                    """, Long.class)).isEqualTo(1);
            System.out.println("Flyway history: V34 successful");
            assertThat(jdbc.queryForObject("select current_database()", String.class))
                    .isEqualTo(ownedName);
        } catch (Exception | AssertionError failure) {
            cleanup();
            throw failure;
        }
    }

    @BeforeEach
    void cleanOwnedRows() {
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo(ownedName);
        jdbc.update("""
                truncate table event_outbox, realtime_signal_outbox, reviews,
                      order_items, payments, orders, cart_items, carts,
                      variant_attribute_values, variants, product_attribute_values,
                      products, customers, users, roles restart identity cascade
                """);
    }

    @AfterAll
    static void cleanup() throws Exception {
        try {
            if (context != null) {
                context.close();
                context = null;
            }
        } finally {
            if (pool != null) {
                pool.close();
                pool = null;
            }
            if (database != null) {
                dropAndVerify(database);
                database = null;
            }
        }
    }

    @Test
    void concurrentDuplicateRejectionReleasesAndWritesOneFact() throws Exception {
        Fixture fixture = seedOrder(OrderStatus.PENDING, PaymentMethod.COD,
                PaymentStatus.PENDING, 5, 2, false);

        Race race = race(() -> reject(fixture), () -> reject(fixture));

        assertThat(List.of(race.first, race.second))
                .as("outcomes: %s / %s", race.first.error, race.second.error)
                .filteredOn(Outcome::isSuccess).hasSize(1);
        assertThat(List.of(race.first, race.second))
                .as("loser: %s / %s", race.first.error, race.second.error)
                .filteredOn(outcome -> outcome.error instanceof InvalidOrderStateException)
                .hasSize(1);
        assertRejected(fixture, 7);
        List<EventRow> events = events(fixture.orderPk());
        assertThat(events).extracting(EventRow::eventType)
                .containsExactly("ORDER_PLACED", "ORDER_REJECTED");
        Instant occurredAt = assertPayload(events.get(1), "ORDER_REJECTED", 2L,
                OrderStatus.CANCELLED, PaymentStatus.FAILED, PaymentMethod.COD,
                OrderEventReason.STAFF_REJECTED, "STAFF_REJECT");
        assertThat(releaseMarker(fixture.orderPk())).isEqualTo(occurredAt);

        assertThatThrownBy(() -> reject(fixture))
                .isInstanceOf(InvalidOrderStateException.class);
        assertRejected(fixture, 7);
        assertThat(events(fixture.orderPk())).hasSize(2);
        System.out.printf("Concurrent rejection: one release/fact, stock=7 (%s)%n",
                fixture.variantId());
    }

    @Test
    void acceptAndRejectAreMutuallyExclusiveInBothOrders() {
        Fixture accepted = seedOrder(OrderStatus.PENDING, PaymentMethod.COD,
                PaymentStatus.PENDING, 5, 2, false);
        assertThat(accept(accepted).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThatThrownBy(() -> reject(accepted))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(orderStatus(accepted.orderPk())).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(variantStock(accepted.variantPk())).isEqualTo(5);
        assertThat(paymentStatus(accepted.orderPk())).isEqualTo(PaymentStatus.PENDING);
        assertThat(releaseMarker(accepted.orderPk())).isNull();
        assertThat(events(accepted.orderPk())).extracting(EventRow::eventType)
                .containsExactly("ORDER_PLACED", "ORDER_CONFIRMED");
        assertPayload(events(accepted.orderPk()).get(1), "ORDER_CONFIRMED", 2L,
                OrderStatus.CONFIRMED, PaymentStatus.PENDING, PaymentMethod.COD,
                null, "STAFF_ACCEPT_COD");

        Fixture rejected = seedOrder(OrderStatus.PENDING, PaymentMethod.COD,
                PaymentStatus.PENDING, 5, 2, false);
        assertThat(reject(rejected).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> accept(rejected))
                .isInstanceOf(InvalidOrderStateException.class);
        assertRejected(rejected, 7);
        assertThat(events(rejected.orderPk())).extracting(EventRow::eventType)
                .containsExactly("ORDER_PLACED", "ORDER_REJECTED");
        System.out.println("Accept vs reject: both lock orders end in one terminal transition");
    }

    @Test
    void unpaidOnlineConfirmationAndCompletionAreDenied() {
        Fixture fixture = seedOrder(OrderStatus.PENDING, PaymentMethod.E_WALLET,
                PaymentStatus.PENDING, 5, 2, false);

        assertThatThrownBy(() -> accept(fixture))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThatThrownBy(() -> complete(fixture))
                .isInstanceOf(InvalidOrderStateException.class);

        assertThat(orderStatus(fixture.orderPk())).isEqualTo(OrderStatus.PENDING);
        assertThat(variantStock(fixture.variantPk())).isEqualTo(5);
        assertThat(paymentStatus(fixture.orderPk())).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentPaidAt(fixture.orderPk())).isNull();
        assertThat(paymentTransactionId(fixture.orderPk())).isNull();
        assertThat(eventSequence(fixture.orderPk())).isEqualTo(1L);
        assertThat(events(fixture.orderPk())).extracting(EventRow::eventType)
                .containsExactly("ORDER_PLACED");
        System.out.println("Unpaid online: confirmation and completion denied, paidAt stays null");
    }

    @Test
    void eligibleCodCompletionPreservesDuePaymentAndWritesOneFact() {
        Fixture fixture = seedOrder(OrderStatus.PENDING, PaymentMethod.COD,
                PaymentStatus.PENDING, 5, 2, false);

        OrderResponse response = complete(fixture);

        assertThat(response.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(orderStatus(fixture.orderPk())).isEqualTo(OrderStatus.COMPLETED);
        assertThat(variantStock(fixture.variantPk())).isEqualTo(5);
        assertThat(paymentStatus(fixture.orderPk())).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentPaidAt(fixture.orderPk())).isNull();
        assertThat(paymentTransactionId(fixture.orderPk())).isNull();
        assertThat(eventSequence(fixture.orderPk())).isEqualTo(2L);
        List<EventRow> events = events(fixture.orderPk());
        assertThat(events).extracting(EventRow::eventType)
                .containsExactly("ORDER_PLACED", "ORDER_COMPLETED");
        assertPayload(events.get(1), "ORDER_COMPLETED", 2L,
                OrderStatus.COMPLETED, PaymentStatus.PENDING, PaymentMethod.COD,
                null, "ADMIN_COMPLETE");

        assertThatThrownBy(() -> complete(fixture))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(events(fixture.orderPk())).hasSize(2);
        assertThat(eventSequence(fixture.orderPk())).isEqualTo(2L);
        System.out.println("COD completion: payment stays due, one ORDER_COMPLETED fact");
    }

    @Test
    void hiddenHistoricalVariantIsRestoredButStaysHidden() {
        Fixture fixture = seedOrder(OrderStatus.PENDING, PaymentMethod.COD,
                PaymentStatus.PENDING, 5, 2, true);
        assertThat(variantRepository.findByVariantId(fixture.variantId())).isEmpty();

        reject(fixture);

        assertRejected(fixture, 7);
        assertThat(jdbc.queryForObject("select is_deleted from variants where id=?",
                Boolean.class, fixture.variantPk())).isTrue();
        assertThat(variantRepository.findByVariantId(fixture.variantId())).isEmpty();
        System.out.printf("Hidden variant: stock restored to 7, row stays hidden (%s)%n",
                fixture.variantId());
    }

    @Test
    void missingStockTargetRollsBackTheWholeRelease() {
        Fixture fixture = seedOrder(OrderStatus.PENDING, PaymentMethod.COD,
                PaymentStatus.PENDING, 5, 2, false);
        transaction.executeWithoutResult(status -> {
            jdbc.execute("set local session_replication_role = replica");
            assertThat(jdbc.update("delete from variants where id=?", fixture.variantPk()))
                    .isEqualTo(1);
        });

        assertThatThrownBy(() -> reject(fixture))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock target not found");

        assertThat(orderStatus(fixture.orderPk())).isEqualTo(OrderStatus.PENDING);
        assertThat(releaseMarker(fixture.orderPk())).isNull();
        assertThat(paymentStatus(fixture.orderPk())).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentPaidAt(fixture.orderPk())).isNull();
        assertThat(eventSequence(fixture.orderPk())).isEqualTo(1L);
        assertThat(events(fixture.orderPk())).hasSize(1);
        System.out.println("Missing stock target: release rolled back before the marker");
    }

    @Test
    void integerOverflowRollsBackTheWholeRelease() {
        Fixture fixture = seedOrder(OrderStatus.PENDING, PaymentMethod.COD,
                PaymentStatus.PENDING, Integer.MAX_VALUE, 1, false);

        assertThatThrownBy(() -> reject(fixture))
                .isInstanceOf(DataAccessException.class);

        assertThat(orderStatus(fixture.orderPk())).isEqualTo(OrderStatus.PENDING);
        assertThat(releaseMarker(fixture.orderPk())).isNull();
        assertThat(paymentStatus(fixture.orderPk())).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentPaidAt(fixture.orderPk())).isNull();
        assertThat(variantStock(fixture.variantPk())).isEqualTo(Integer.MAX_VALUE);
        assertThat(eventSequence(fixture.orderPk())).isEqualTo(1L);
        assertThat(events(fixture.orderPk())).hasSize(1);
        System.out.println("Stock overflow: release rolled back before the marker");
    }

    @Test
    void sequencesAdvanceOncePerAcceptedTransition() {
        Fixture fixture = seedOrder(OrderStatus.PENDING, PaymentMethod.COD,
                PaymentStatus.PENDING, 5, 2, false);

        accept(fixture);
        complete(fixture);

        assertThat(orderStatus(fixture.orderPk())).isEqualTo(OrderStatus.COMPLETED);
        assertThat(paymentStatus(fixture.orderPk())).isEqualTo(PaymentStatus.PENDING);
        assertThat(eventSequence(fixture.orderPk())).isEqualTo(3L);
        List<EventRow> events = events(fixture.orderPk());
        assertThat(events).extracting(EventRow::eventType)
                .containsExactly("ORDER_PLACED", "ORDER_CONFIRMED", "ORDER_COMPLETED");
        assertThat(events).extracting(EventRow::orderSequence)
                .containsExactly(1L, 2L, 3L);
        assertPayload(events.get(1), "ORDER_CONFIRMED", 2L,
                OrderStatus.CONFIRMED, PaymentStatus.PENDING, PaymentMethod.COD,
                null, "STAFF_ACCEPT_COD");
        assertPayload(events.get(2), "ORDER_COMPLETED", 3L,
                OrderStatus.COMPLETED, PaymentStatus.PENDING, PaymentMethod.COD,
                null, "ADMIN_COMPLETE");
        System.out.println("Sequences: PLACED=1, CONFIRMED=2, COMPLETED=3");
    }

    private static OrderResponse accept(Fixture fixture) {
        return orderService.updateStatusByStaff(fixture.orderId(),
                new StaffOrderStatusUpdateRequest(OrderStatus.CONFIRMED));
    }

    private static OrderResponse reject(Fixture fixture) {
        return orderService.updateStatusByStaff(fixture.orderId(),
                new StaffOrderStatusUpdateRequest(OrderStatus.CANCELLED));
    }

    private static OrderResponse complete(Fixture fixture) {
        return orderService.completeByAdmin(fixture.orderId());
    }

    private static void assertRejected(Fixture fixture, int expectedStock) {
        assertThat(orderStatus(fixture.orderPk())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("select cancellation_reason from orders where id=?",
                String.class, fixture.orderPk())).isEqualTo("STAFF_REJECTED");
        assertThat(variantStock(fixture.variantPk())).isEqualTo(expectedStock);
        assertThat(paymentStatus(fixture.orderPk())).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentPaidAt(fixture.orderPk())).isNull();
        assertThat(paymentTransactionId(fixture.orderPk())).isNull();
        assertThat(releaseMarker(fixture.orderPk())).isNotNull();
        assertThat(eventSequence(fixture.orderPk())).isEqualTo(2L);
    }

    private static Instant assertPayload(EventRow row, String type, long sequence,
                                         OrderStatus orderStatus, PaymentStatus paymentStatus,
                                         PaymentMethod method, OrderEventReason reason,
                                         String entryPoint) {
        assertThat(row.eventType()).isEqualTo(type);
        assertThat(row.orderSequence()).isEqualTo(sequence);
        JsonNode payload = mapper.readTree(row.payload());
        assertThat(payload.get("eventType").asString()).isEqualTo(type);
        assertThat(payload.get("orderSequence").asLong()).isEqualTo(sequence);
        assertThat(payload.get("orderStatus").asString()).isEqualTo(orderStatus.name());
        assertThat(payload.get("paymentStatus").asString()).isEqualTo(paymentStatus.name());
        assertThat(payload.get("paymentMethod").asString()).isEqualTo(method.name());
        assertThat(payload.get("entryPoint").asString()).isEqualTo(entryPoint);
        JsonNode reasonNode = payload.get("reason");
        if (reason == null) {
            assertThat(reasonNode == null || reasonNode.isNull()).isTrue();
        } else {
            assertThat(reasonNode.asString()).isEqualTo(reason.name());
        }
        return Instant.parse(payload.get("occurredAt").asString());
    }

    private static List<EventRow> events(long orderPk) {
        return jdbc.query("""
                select order_sequence, event_type, payload::text
                  from event_outbox
                 where order_id = ?
                 order by order_sequence
                """, (rows, index) -> new EventRow(
                rows.getLong(1), rows.getString(2), rows.getString(3)), orderPk);
    }

    private static OrderStatus orderStatus(long orderPk) {
        return OrderStatus.valueOf(jdbc.queryForObject(
                "select order_status from orders where id=?", String.class, orderPk));
    }

    private static PaymentStatus paymentStatus(long orderPk) {
        return PaymentStatus.valueOf(jdbc.queryForObject(
                "select payment_status from payments where order_id=?",
                String.class, orderPk));
    }

    private static Instant releaseMarker(long orderPk) {
        Timestamp marker = jdbc.queryForObject(
                "select stock_released_at from orders where id=?", Timestamp.class, orderPk);
        return marker == null ? null : marker.toInstant();
    }

    private static Instant paymentPaidAt(long orderPk) {
        Timestamp paidAt = jdbc.queryForObject(
                "select paid_at from payments where order_id=?", Timestamp.class, orderPk);
        return paidAt == null ? null : paidAt.toInstant();
    }

    private static String paymentTransactionId(long orderPk) {
        return jdbc.queryForObject(
                "select payment_transaction_id from payments where order_id=?",
                String.class, orderPk);
    }

    private static long eventSequence(long orderPk) {
        return jdbc.queryForObject(
                "select event_sequence from orders where id=?", Long.class, orderPk);
    }

    private static int variantStock(long variantPk) {
        return jdbc.queryForObject(
                "select variant_stock from variants where id=?", Integer.class, variantPk);
    }

    private static Race race(Supplier<Object> first, Supplier<Object> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Outcome> one = () -> {
                start.await();
                return attempt(first);
            };
            Callable<Outcome> two = () -> {
                start.await();
                return attempt(second);
            };
            Future<Outcome> firstFuture = pool.submit(one);
            Future<Outcome> secondFuture = pool.submit(two);
            start.countDown();
            return new Race(firstFuture.get(20, TimeUnit.SECONDS),
                    secondFuture.get(20, TimeUnit.SECONDS));
        }
    }

    private static Outcome attempt(Supplier<Object> action) {
        try {
            return new Outcome(action.get(), null);
        } catch (RuntimeException failure) {
            return new Outcome(null, failure);
        }
    }

    private static Fixture seedOrder(OrderStatus status, PaymentMethod method,
                                     PaymentStatus paymentStatus, int stock, int quantity,
                                     boolean variantDeleted) {
        String suffix = UUID.randomUUID().toString();
        String variantId = "VAR_" + suffix;
        String orderId = "ORD_" + suffix;
        long role = jdbc.queryForObject("""
                insert into roles(role_id, role_code, role_name)
                values (?, ?, 'Fixture') returning id
                """, Long.class, "ROL_" + suffix, "role-" + suffix);
        long user = jdbc.queryForObject("""
                insert into users(user_id, user_email, user_password_hash, role_id)
                values (?, ?, 'unused', ?) returning id
                """, Long.class, "USR_" + suffix, suffix + "@example.invalid", role);
        long customer = jdbc.queryForObject("""
                insert into customers(customer_id, user_id, customer_name, customer_phone)
                values (?, ?, 'Fixture', '000') returning id
                """, Long.class, "CUS_" + suffix, user);
        long product = jdbc.queryForObject("""
                insert into products(product_id, product_name, product_description,
                                     product_status, product_slug)
                values (?, 'Fixture', 'fixture', 'ACTIVE', ?) returning id
                """, Long.class, "PRD_" + suffix, "fixture-" + suffix);
        long variant = jdbc.queryForObject("""
                insert into variants(variant_id, product_id, variant_sku,
                                     variant_price, variant_stock, is_deleted)
                values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, variantId, product, suffix,
                new BigDecimal("10.00"), stock, variantDeleted);
        BigDecimal total = new BigDecimal("10.00").multiply(BigDecimal.valueOf(quantity));
        long orderPk = jdbc.queryForObject("""
                insert into orders(order_id, customer_id, order_status, order_total,
                                   order_address, order_recipient_name, order_phone,
                                   event_sequence)
                values (?, ?, ?, ?, 'fixture', 'Fixture', '000', 1) returning id
                """, Long.class, orderId, customer, status.name(), total);
        jdbc.update("""
                insert into order_items(order_id, variant_id, quantity, unit_price)
                values (?, ?, ?, ?)
                """, orderPk, variant, quantity, new BigDecimal("10.00"));
        jdbc.update("""
                insert into payments(payment_id, order_id, payment_method, payment_status,
                                     payment_amount)
                values (?, ?, ?, ?, ?)
                """, "PAY_" + suffix, orderPk, method.name(), paymentStatus.name(), total);
        appendSeedEvent(orderPk, 1L, "ORDER_PLACED", orderId);
        return new Fixture(orderPk, orderId, variant, variantId);
    }

    private static void appendSeedEvent(long orderPk, long sequence, String type,
                                        String orderId) {
        String payload = "{\"orderId\":\"" + orderId + "\",\"orderSequence\":" + sequence
                + ",\"eventType\":\"" + type + "\"}";
        jdbc.update("""
                insert into event_outbox(event_id, order_id, order_sequence,
                                         event_type, schema_version, payload, available_at)
                values (?, ?, ?, ?, 1, ?::jsonb, clock_timestamp())
                """, UUID.randomUUID(), orderPk, sequence, type, payload);
    }

    private static String nameOf(ExternalPostgresDatabase owned) {
        return URI.create(owned.jdbcUrl().substring("jdbc:".length())).getPath().substring(1);
    }

    private static void verifyIdentity(ExternalPostgresDatabase owned) throws Exception {
        assertThat(nameOf(owned)).matches("verify_event_foundation_[0-9a-f]{12}");
        try (Connection connection = DriverManager.getConnection(
                owned.jdbcUrl(), owned.username(), owned.password());
             var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select current_database()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(nameOf(owned));
        }
        System.out.println("Verified Task5 scratch identity: " + nameOf(owned));
    }

    private static void dropAndVerify(ExternalPostgresDatabase owned) throws Exception {
        owned.drop();
        try (Connection connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv("TEST_POSTGRES_USERNAME"), System.getenv("TEST_POSTGRES_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(
                     "select 1 from pg_database where datname=?")) {
            statement.setString(1, nameOf(owned));
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isFalse();
            }
        }
        System.out.println("Confirmed Task5 scratch cleanup: " + nameOf(owned));
    }

    private record Fixture(long orderPk, String orderId, long variantPk, String variantId) { }

    private record EventRow(long orderSequence, String eventType, String payload) { }

    private record Outcome(Object value, RuntimeException error) {
        private boolean isSuccess() {
            return error == null;
        }
    }

    private record Race(Outcome first, Outcome second) { }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = "com.project.dacn.repository")
    @Import({OrderService.class, OrderLifecycleService.class, OrderResponseAssembler.class})
    static class Config {
        @Bean
        OrderMapper orderMapper() {
            return org.mapstruct.factory.Mappers.getMapper(OrderMapper.class);
        }

        @Bean
        PaymentMapper paymentMapper() {
            return org.mapstruct.factory.Mappers.getMapper(PaymentMapper.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        DatabaseClock databaseClock(JdbcTemplate jdbcTemplate) {
            return new DatabaseClock(jdbcTemplate);
        }

        @Bean
        OrderEventOutbox orderEventOutbox(JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
            return new OrderEventOutbox(jdbcTemplate, new OrderEventCodec(mapper));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.project.dacn.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "none", "hibernate.show_sql", "false"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        static PersistenceExceptionTranslationPostProcessor exceptionTranslation() {
            return new PersistenceExceptionTranslationPostProcessor();
        }
    }
}
