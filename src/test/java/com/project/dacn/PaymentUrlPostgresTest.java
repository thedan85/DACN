package com.project.dacn;

import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.controller.payment.PaymentController;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.integration.payment.momo.*;
import com.project.dacn.integration.payment.vnpay.*;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.security.CustomerIdentity;
import com.project.dacn.service.cart.CartService;
import com.project.dacn.service.order.OrderLifecycleService;
import com.project.dacn.service.order.ReservationPolicy;
import com.project.dacn.service.payment.PaymentService;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL URL locks, refreshes and write-free adapter quarantine; no provider I/O. */
class PaymentUrlPostgresTest {
    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static HikariDataSource pool;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static EntityManager entityManager;
    private static ControlledClock clock;
    private static PaymentService service;
    private static ReservationPolicy originalPolicy;

    @BeforeAll
    static void startOwnedDatabase() throws Exception {
        database = ExternalPostgresDatabase.create();
        ownedName = URI.create(database.jdbcUrl().substring(5)).getPath().substring(1);
        try {
            assertThat(ownedName).matches("verify_event_foundation_[0-9a-f]{12}");
            try (Connection connection = connect(); var statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select current_database()")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(ownedName);
            }
            System.out.println("Verified Task8 scratch identity: " + ownedName);
            database.migrate();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(database.jdbcUrl());
            config.setUsername(database.username());
            config.setPassword(database.password());
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            pool = new HikariDataSource(config);
            context = new AnnotationConfigApplicationContext();
            context.registerBean(DataSource.class, () -> pool);
            context.register(Config.class);
            context.refresh();
            jdbc = context.getBean(JdbcTemplate.class);
            transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            entityManager = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
            clock = context.getBean(ControlledClock.class);
            service = context.getBean(PaymentService.class);
            originalPolicy = context.getBean(ReservationPolicy.class);
        } catch (Exception | AssertionError failure) {
            cleanup();
            throw failure;
        }
    }

    @BeforeEach
    void clean() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(ownedName);
        jdbc.execute("truncate event_outbox, realtime_signal_outbox, reviews, order_items, payments, orders, "
                + "cart_items, carts, variant_attribute_values, variants, product_attribute_values, "
                + "products, customers, users, roles restart identity cascade");
        clock.fixed.set(null);
        clock.samples.set(0);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (context != null) context.close();
        if (pool != null) pool.close();
        if (database != null) {
            database.drop();
            try (Connection connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                    System.getenv("TEST_POSTGRES_USERNAME"), System.getenv("TEST_POSTGRES_PASSWORD"));
                 PreparedStatement query = connection.prepareStatement("select 1 from pg_database where datname=?")) {
                query.setString(1, ownedName);
                try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isFalse(); }
            }
            System.out.println("Confirmed Task8 scratch cleanup: " + ownedName);
            database = null;
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void persistedDeadlineUsesExactDatabaseDerivedBoundaryWithoutAnyWrite(long microseconds) {
        Fixture f = checkout();
        Instant deadline = deadline(f);
        clock.fixed.set(deadline.plusNanos(microseconds * 1_000));
        Map<String, Object> before = snapshot(f);
        if (microseconds < 0) {
            Map<String, String> fields = query(url(f));
            assertThat(context.getBean(VnpaySignatureService.class).verifyParams(fields)).isTrue();
            assertThat(fields.get("vnp_ExpireDate")).isEqualTo(providerTime(deadline));
            assertThat(fields).containsEntry("vnp_TxnRef", paymentId(f)).containsEntry("vnp_Amount", "3000");
            assertThat(query(url(f)).get("vnp_ExpireDate")).isEqualTo(fields.get("vnp_ExpireDate"));
        } else {
            assertThatThrownBy(() -> url(f)).isInstanceOf(InvalidOrderStateException.class);
        }
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders", "payments"})
    void heldOrderOrPaymentTimesOutAtTwoSecondsAndDoesNotWrite(String table) throws Exception {
        Fixture f = checkout();
        Map<String, Object> before = snapshot(f);
        try (Connection holder = connect(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from " + table
                    + (table.equals("orders") ? " where id=? for update" : " where order_id=? for update"))) {
                lock.setLong(1, f.id()); lock.executeQuery().close();
            }
            long started = System.nanoTime();
            Future<Outcome> waiter = executor.submit(() -> attempt(() -> url(f)));
            try {
                Outcome result = waiter.get(8, TimeUnit.SECONDS);
                assertThat(result.error()).isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(1500L, 5000L);
                assertThat(clock.samples.get()).isZero();
                assertThat(snapshot(f)).isEqualTo(before);
            } finally { holder.rollback(); }
        }
    }

    @Test
    void paymentWaitAlreadyOwnsOrderAndOnlySamplesClockAfterBothLocks() throws Exception {
        Fixture f = checkout();
        Map<String, Object> before = snapshot(f);
        clock.fixed.set(deadline(f).minusNanos(1_000));
        try (Connection holder = connect(); Connection observer = connect(); Connection probe = connect();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from payments where order_id=? for update")) {
                lock.setLong(1, f.id()); lock.executeQuery().close();
            }
            AtomicInteger waiterPid = new AtomicInteger();
            Future<Outcome> waiter = executor.submit(() -> attempt(() -> transaction.execute(status -> {
                waiterPid.set(pid()); return url(f);
            })));
            try {
                assertThat(awaitBlocked(observer, backendPid(holder), waiterPid, waiter)).containsIgnoringCase("payments");
                assertThat(clock.samples.get()).isZero();
                try (var statement = probe.createStatement()) {
                    assertThatThrownBy(() -> statement.executeQuery("select id from orders where id=" + f.id() + " for update nowait"))
                            .isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("55P03"));
                }
            } finally { holder.rollback(); }
            assertThat(waiter.get(8, TimeUnit.SECONDS).error()).isNull();
        }
        assertThat(clock.samples.get()).isEqualTo(1);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deadline", "order-status", "payment-status", "payment-amount"})
    void staleManagedStateIsRefreshedAfterObservableLockWait(String change) throws Exception {
        Fixture f = checkout();
        Instant deadline = deadline(f);
        clock.fixed.set(deadline.minusNanos(1_000));
        boolean paymentChange = change.startsWith("payment");
        try (Connection holder = connect(); Connection observer = connect();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            String sql = switch (change) {
                case "deadline" -> "update orders set payment_expires_at=payment_expires_at-interval '1 minute' where id=?";
                case "order-status" -> "update orders set order_status='CANCELLED' where id=?";
                case "payment-status" -> "update payments set payment_status='CONFIRMED' where order_id=?";
                default -> "update payments set payment_amount=31 where order_id=?";
            };
            try (PreparedStatement update = holder.prepareStatement(sql)) {
                update.setLong(1, f.id()); assertThat(update.executeUpdate()).isEqualTo(1);
            }
            AtomicInteger waiterPid = new AtomicInteger();
            Future<Outcome> waiter = executor.submit(() -> attempt(() -> transaction.execute(status -> {
                // Deliberately preload stale entities in the same persistence context before the locked call.
                context.getBean(OrderRepository.class).findByOrderId(f.orderId()).orElseThrow();
                context.getBean(PaymentRepository.class).findByOrder_OrderId(f.orderId()).orElseThrow();
                waiterPid.set(pid()); return url(f);
            })));
            try {
                assertThat(awaitBlocked(observer, backendPid(holder), waiterPid, waiter))
                        .containsIgnoringCase(paymentChange ? "payments" : "orders");
                assertThat(clock.samples.get()).isZero();
            } finally { holder.commit(); }
            Map<String, Object> committed = snapshot(f);
            Outcome result = waiter.get(8, TimeUnit.SECONDS);
            assertThat(result.error()).isInstanceOf(change.equals("deadline") || change.equals("payment-amount")
                    ? InvalidOrderStateException.class : IllegalArgumentException.class);
            assertThat(result.value()).isNull();
            assertThat(snapshot(f)).isEqualTo(committed);
        }
    }

    @Test
    void deadlineWaitSamplesFreshTimeRatherThanAdmissionTime() throws Exception {
        Fixture f = checkout();
        Instant deadline = deadline(f);
        clock.fixed.set(deadline.minusNanos(1_000));
        Map<String, Object> before = snapshot(f);
        try (Connection holder = connect(); Connection observer = connect();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from orders where id=? for update")) {
                lock.setLong(1, f.id()); lock.executeQuery().close();
            }
            AtomicInteger waiterPid = new AtomicInteger();
            Future<Outcome> waiter = executor.submit(() -> attempt(() -> transaction.execute(status -> {
                waiterPid.set(pid()); return url(f);
            })));
            try {
                assertThat(awaitBlocked(observer, backendPid(holder), waiterPid, waiter)).containsIgnoringCase("orders");
                assertThat(clock.samples.get()).isZero();
                clock.fixed.set(deadline); // Deliberate exact equality after admission, before lock release.
            } finally { holder.rollback(); }
            assertThat(waiter.get(8, TimeUnit.SECONDS).error()).isInstanceOf(InvalidOrderStateException.class);
        }
        assertThat(clock.samples.get()).isEqualTo(1);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @Test
    void transactionLocalBoundResetsAfterSuccessfulIssuanceAndRejectedTransaction() throws Exception {
        Fixture f = checkout();
        try (Connection a = pool.getConnection(); Connection b = pool.getConnection();
             Connection c = pool.getConnection(); Connection d = pool.getConnection()) {
            int used = transaction.execute(status -> {
                assertThat(entityManager.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo("0");
                url(f);
                assertThat(entityManager.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo("2s");
                return pid();
            });
            try (Connection holder = connect()) {
                holder.setAutoCommit(false);
                try (PreparedStatement lock = holder.prepareStatement("select id from payments where order_id=? for update")) {
                    lock.setLong(1, f.id()); lock.executeQuery().close();
                }
                try {
                    assertThatThrownBy(() -> transaction.execute(status -> {
                        assertThat(pid()).isEqualTo(used);
                        assertThat(entityManager.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo("0");
                        return url(f);
                    })).isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
                } finally { holder.rollback(); }
            }
            clock.fixed.set(deadline(f));
            assertThatThrownBy(() -> transaction.execute(status -> {
                assertThat(pid()).isEqualTo(used);
                assertThat(entityManager.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo("0");
                return url(f);
            })).isInstanceOf(InvalidOrderStateException.class);
            transaction.executeWithoutResult(status -> {
                assertThat(pid()).isEqualTo(used);
                assertThat(entityManager.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo("0");
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void pauseAndResumeKeepStoredFactsAndTheSameActiveDeadline(boolean active, boolean online, boolean sweep) {
        Fixture f = checkout();
        Instant cutoff = deadline(f);
        clock.fixed.set(cutoff.minusSeconds(1));
        Map<String, Object> before = snapshot(f);
        if (online) {
            withPolicy(new ReservationPolicy(active, true, sweep), () -> {
                assertThatThrownBy(() -> url(f)).isInstanceOf(InvalidOrderStateException.class);
                return null;
            });
            assertThat(snapshot(f)).isEqualTo(before);
        }
        withPolicy(new ReservationPolicy(active, false, sweep), () -> {
            Map<String, String> first = query(url(f));
            Map<String, String> second = query(url(f));
            var signature = context.getBean(VnpaySignatureService.class);
            assertThat(signature.verifyParams(first)).isTrue();
            assertThat(signature.verifyParams(second)).isTrue();
            if (active) {
                assertThat(first.get("vnp_ExpireDate")).isEqualTo(providerTime(cutoff));
                assertThat(second.get("vnp_ExpireDate")).isEqualTo(first.get("vnp_ExpireDate"));
                clock.fixed.set(cutoff);
                assertThatThrownBy(() -> url(f)).isInstanceOf(InvalidOrderStateException.class);
            } else {
                assertLegacyWindow(first);
                assertLegacyWindow(second);
            }
            return null;
        });
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inactiveOldOrdersWithNullOrPastProposalsStillReceiveSignedLegacyWindow(boolean proposed) {
        Fixture f = checkout();
        jdbc.update("update orders set created_at=clock_timestamp()-interval '2 days', payment_expires_at=? where id=?",
                proposed ? Timestamp.from(Instant.parse("2020-01-01T00:15:00Z")) : null, f.id());
        Map<String, Object> before = snapshot(f);
        withPolicy(new ReservationPolicy(false, false, true), () -> {
            Map<String, String> fields = query(url(f));
            assertThat(context.getBean(VnpaySignatureService.class).verifyParams(fields)).isTrue();
            assertLegacyWindow(fields);
            return null;
        });
        assertThat(snapshot(f)).isEqualTo(before);
    }

    private static void assertLegacyWindow(Map<String, String> fields) {
        var format = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        var created = java.time.LocalDateTime.parse(fields.get("vnp_CreateDate"), format);
        assertThat(java.time.LocalDateTime.parse(fields.get("vnp_ExpireDate"), format)).isEqualTo(created.plusMinutes(15));
        assertThat(created.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()).isAfter(Instant.now().minusSeconds(60));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "expired", "null"})
    void actualPaymentControllerUsesSafeConflictEnvelopeWithoutUrlOrWrites(String reason) throws Exception {
        Fixture f = checkout();
        if (reason.equals("expired")) clock.fixed.set(deadline(f));
        if (reason.equals("null")) jdbc.update("update orders set payment_expires_at=null where id=?", f.id());
        Map<String, Object> before = snapshot(f);
        CustomerIdentity identity = mock(CustomerIdentity.class);
        when(identity.requireCustomerId(any())).thenReturn(f.customer());
        var mvc = MockMvcBuilders.standaloneSetup(new PaymentController(service, identity))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        withPolicy(reason.equals("pause") ? new ReservationPolicy(true, true, true) : originalPolicy, () -> {
            try {
                mvc.perform(post("/api/v1/payments/vnpay/" + f.orderId()))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                        .andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.payUrl").doesNotExist());
            } catch (Exception error) { throw new RuntimeException(error); }
            return null;
        });
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void signedActualProviderAdaptersRejectReviewWithNoLifecycleWrites(boolean success) {
        Fixture f = checkout();
        jdbc.update("update orders set payment_expires_at=null where id=?", f.id());
        Map<String, Object> before = snapshot(f);
        VnpaySignatureService signature = context.getBean(VnpaySignatureService.class);
        Map<String, String> params = new LinkedHashMap<>(Map.of("vnp_TmnCode", "TMN01",
                "vnp_TxnRef", paymentId(f), "vnp_Amount", "3000", "vnp_TransactionNo", "review-money",
                "vnp_ResponseCode", success ? "00" : "24", "vnp_TransactionStatus", success ? "00" : "02"));
        params.put("vnp_SecureHash", signature.sign(signature.canonical(params)));
        assertThat(signature.verifyParams(params)).isTrue();
        var vnpay = new VnpayWebhookController(signature, service, context.getBean(VnpayProperties.class));
        withPolicy(new ReservationPolicy(true, true, true), () -> {
            assertThat(vnpay.ipn(params)).containsEntry("RspCode", "99");
            assertThat(snapshot(f)).isEqualTo(before);
            return null;
        });
        MomoProperties properties = new MomoProperties();
        properties.setAccessKey("test-access"); properties.setSecretKey("test-only-secret");
        MomoSignatureService momoSignature = new MomoSignatureService(properties);
        String result = success ? "0" : "1006";
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("orderId", f.orderId(), "amount", "30",
                "resultCode", result, "transId", "review-money"));
        String canonical = "accessKey=test-access&amount=30&extraData=&message=&orderId=" + f.orderId()
                + "&orderInfo=&orderType=&partnerCode=&payType=&requestId=&responseTime=&resultCode=" + result + "&transId=review-money";
        payload.put("signature", momoSignature.sign(canonical));
        assertThat(momoSignature.verify(canonical, (String) payload.get("signature"))).isTrue();
        var momo = new MomoWebhookController(momoSignature, properties, context.getBean(PaymentSettlementService.class));
        var response = momo.handleWebhook(payload);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).isEqualTo("reservation requires review");
        assertThat(response.getBody().getData()).isNull();
        assertThat(snapshot(f)).isEqualTo(before);
    }

    private static <T> T withPolicy(ReservationPolicy policy, Supplier<T> action) {
        Object paymentTarget = AopTestUtils.getTargetObject(service);
        Object lifecycleTarget = AopTestUtils.getTargetObject(context.getBean(OrderLifecycleService.class));
        ReflectionTestUtils.setField(paymentTarget, "reservationPolicy", policy);
        ReflectionTestUtils.setField(lifecycleTarget, "reservationPolicy", policy);
        try { return action.get(); }
        finally {
            ReflectionTestUtils.setField(paymentTarget, "reservationPolicy", originalPolicy);
            ReflectionTestUtils.setField(lifecycleTarget, "reservationPolicy", originalPolicy);
        }
    }

    private static Fixture checkout() {
        String suffix = UUID.randomUUID().toString();
        long role = jdbc.queryForObject("insert into roles(role_id,role_code,role_name) values (?,?,'Fixture') returning id", Long.class, "R" + suffix, suffix);
        long user = jdbc.queryForObject("insert into users(user_id,user_email,user_password_hash,role_id) values (?,?,'unused',?) returning id", Long.class, "U" + suffix, suffix + "@example.invalid", role);
        long customer = jdbc.queryForObject("insert into customers(customer_id,user_id,customer_name,customer_phone) values (?,?,'Fixture','000') returning id", Long.class, "C" + suffix, user);
        long product = jdbc.queryForObject("insert into products(product_id,product_name,product_description,product_status,product_slug) values (?,'Fixture','fixture','ACTIVE',?) returning id", Long.class, "P" + suffix, suffix);
        long variant = jdbc.queryForObject("insert into variants(variant_id,product_id,variant_sku,variant_price,variant_stock) values (?,?,?,10,10) returning id", Long.class, "V" + suffix, product, suffix);
        long cart = jdbc.queryForObject("insert into carts(cart_id,customer_id) values (?,?) returning id", Long.class, "CRT" + suffix, customer);
        jdbc.update("insert into cart_items(cart_item_id,cart_id,variant_id,quantity) values (?,?,?,3)", "CI" + suffix, cart, variant);
        var response = context.getBean(CartService.class).checkout(customer,
                new CartCheckoutRequest("fixture", "Fixture", "000", PaymentMethod.E_WALLET));
        long id = jdbc.queryForObject("select id from orders where order_id=?", Long.class, response.orderId());
        clock.samples.set(0);
        return new Fixture(id, response.orderId(), customer);
    }

    private static String url(Fixture f) { return service.createVnpayUrl(f.customer(), f.orderId(), "127.0.0.1").payUrl(); }
    private static String paymentId(Fixture f) { return jdbc.queryForObject("select payment_id from payments where order_id=?", String.class, f.id()); }
    private static Instant deadline(Fixture f) { return jdbc.queryForObject("select payment_expires_at from orders where id=?", Timestamp.class, f.id()).toInstant(); }
    private static String providerTime(Instant time) { return java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(time); }
    private static Map<String, String> query(String url) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            fields.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return fields;
    }
    private static Map<String, Object> snapshot(Fixture f) {
        return Map.of("order", jdbc.queryForMap("select * from orders where id=?", f.id()),
                "payments", jdbc.queryForList("select * from payments where order_id=?", f.id()),
                "items", jdbc.queryForList("select * from order_items where order_id=? order by id", f.id()),
                "stock", jdbc.queryForList("select * from variants order by id"),
                "events", jdbc.queryForList("select * from event_outbox order by id"),
                "signals", jdbc.queryForList("select * from realtime_signal_outbox order by id"));
    }
    private static String awaitBlocked(Connection observer, int holder, AtomicInteger waiter, Future<?> future) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            try (PreparedStatement query = observer.prepareStatement("select query from pg_stat_activity where pid=? "
                    + "and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid))")) {
                query.setInt(1, waiter.get()); query.setInt(2, holder);
                try (ResultSet rows = query.executeQuery()) { if (rows.next()) return rows.getString(1); }
            }
            assertThat(future.isDone()).as("waiter must overlap held transaction").isFalse();
        }
        throw new AssertionError("No observable PostgreSQL lock overlap");
    }
    private static Outcome attempt(Supplier<Object> action) {
        try { return new Outcome(action.get(), null); }
        catch (RuntimeException error) { return new Outcome(null, error); }
    }
    private static int pid() { return ((Number) entityManager.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue(); }
    private static int backendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("select pg_backend_pid()")) {
            rows.next(); return rows.getInt(1);
        }
    }
    private static Connection connect() throws Exception { return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password()); }
    private record Fixture(long id, String orderId, long customer) { }
    private record Outcome(Object value, RuntimeException error) { }

    /** Uses real clock_timestamp() except explicitly controlled database-derived cutoff cases. */
    static class ControlledClock extends DatabaseClock {
        final AtomicReference<Instant> fixed = new AtomicReference<>();
        final AtomicInteger samples = new AtomicInteger();
        ControlledClock(JdbcTemplate jdbc) { super(jdbc); }
        @Override public Instant now() {
            samples.incrementAndGet();
            Instant selected = fixed.get();
            return selected == null ? super.now() : selected;
        }
    }

    @Configuration
    @Import({CheckoutInventoryPostgresTest.Config.class, PaymentSettlementService.class, PaymentService.class})
    static class Config {
        @Bean @Primary ControlledClock controlledClock(JdbcTemplate jdbc) { return new ControlledClock(jdbc); }
        @Bean VnpayProperties vnpayProperties() {
            var properties = new VnpayProperties();
            properties.setTmnCode("TMN01"); properties.setHashSecret("test-only-secret");
            properties.setPayUrl("https://provider.example.invalid/pay");
            properties.setReturnUrl("http://localhost/api/v1/webhooks/vnpay/return");
            return properties;
        }
        @Bean VnpaySignatureService signatureService(VnpayProperties properties) { return new VnpaySignatureService(properties); }
        @Bean VnpayUrlBuilder urlBuilder(VnpayProperties properties, VnpaySignatureService signature) { return new VnpayUrlBuilder(properties, signature); }
    }
}
