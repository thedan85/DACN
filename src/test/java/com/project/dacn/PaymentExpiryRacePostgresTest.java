package com.project.dacn;

import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.integration.payment.momo.*;
import com.project.dacn.integration.payment.vnpay.*;
import com.project.dacn.service.payment.PaymentService;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.cart.CartService;
import com.project.dacn.service.order.OrderLifecycleService;
import com.project.dacn.service.order.ReservationPolicy;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.project.dacn.integration.payment.PaymentSettlementService.Result.*;
import static org.assertj.core.api.Assertions.*;

/** Callback-driven expiry only. Scheduled SKIP LOCKED claims belong to Task 9. */
class PaymentExpiryRacePostgresTest {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentExpiryRacePostgresTest.class);
    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static HikariDataSource pool;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static EntityManager entityManager;
    private static ControlledClock clock;
    private static PaymentSettlementService settlement;
    private static OrderLifecycleService lifecycle;
    private static ReservationPolicy originalPolicy;
    private static ObjectMapper mapper;

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
            log.info("Verified Task6 scratch identity: {}", ownedName);
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
            settlement = context.getBean(PaymentSettlementService.class);
            lifecycle = context.getBean(OrderLifecycleService.class);
            originalPolicy = context.getBean(ReservationPolicy.class);
            mapper = context.getBean(ObjectMapper.class);
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
        clock.fixed.remove();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (context != null) context.close();
        if (pool != null) pool.close();
        if (database != null) {
            database.drop();
            try (Connection connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                    System.getenv("TEST_POSTGRES_USERNAME"), System.getenv("TEST_POSTGRES_PASSWORD"));
                 PreparedStatement statement = connection.prepareStatement("select 1 from pg_database where datname=?")) {
                statement.setString(1, ownedName);
                try (ResultSet rows = statement.executeQuery()) { assertThat(rows.next()).isFalse(); }
            }
            log.info("Confirmed Task6 scratch cleanup: {}", ownedName);
            database = null;
        }
    }

    @Test
    void realCheckoutThenPausedEqualityCallbackReleasesOnceAndRecordsConsecutiveSnapshots() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        assertThat(stock(f)).isEqualTo(7);
        assertFacts(f, "ORDER_PLACED");
        withPolicy(new ReservationPolicy(true, true, true), () -> {
            clock.fixed.set(deadline(f)); // Exact database-derived equality, not wall-clock coincidence.
            assertThat(pay(f, true, "received")).isEqualTo(REFUND_REQUIRED);
            return null;
        });
        assertThat(stock(f)).isEqualTo(10);
        assertCancelled(f, "REFUND_REQUIRED", "PAYMENT_EXPIRED");
        assertFacts(f, "ORDER_PLACED", "ORDER_EXPIRED", "REFUND_REQUIRED");
        assertFact(f, 2, "CANCELLED", "FAILED", "PAYMENT_EXPIRED");
        assertFact(f, 3, "CANCELLED", "REFUND_REQUIRED", null);
        assertThat(jdbc.queryForObject("select stock_released_at from orders where id=?", Timestamp.class, f.id()).toInstant())
                .isEqualTo(deadline(f));
        assertThat(jdbc.queryForObject("select paid_at from payments where order_id=?", Timestamp.class, f.id()).toInstant())
                .isEqualTo(deadline(f));
        assertThat(jdbc.queryForObject("select payment_transaction_id from payments where order_id=?", String.class, f.id()))
                .isEqualTo("received");
        Map<String, Object> before = snapshot(f);
        assertThat(pay(f, true, "overwrite")).isEqualTo(ALREADY_SETTLED);
        assertThat(pay(f, false, null)).isEqualTo(ALREADY_SETTLED);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @Test
    void successBeforeCutoffSurvivesPausedStaleExpiryAndPreservesReceivedEvidence() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        clock.fixed.set(deadline(f).minusNanos(1_000));
        assertThat(pay(f, true, "first-money")).isEqualTo(SETTLED);
        assertThat(stock(f)).isEqualTo(7);
        assertFacts(f, "ORDER_PLACED", "ORDER_CONFIRMED");
        assertFact(f, 2, "CONFIRMED", "CONFIRMED", null);
        Map<String, Object> before = snapshot(f);
        withPolicy(new ReservationPolicy(true, true, true), () -> {
            clock.fixed.set(deadline(f).plusSeconds(60));
            assertThat(pay(f, false, null)).isEqualTo(ALREADY_SETTLED);
            assertThat(pay(f, true, "second-money")).isEqualTo(ALREADY_SETTLED);
            return null;
        });
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void overlappingPaymentAndStaffRejectionForceBothWinners(boolean paymentFirst) throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        Instant beforeDeadline = deadline(f).minusSeconds(1);
        Supplier<Object> payment = () -> at(beforeDeadline, () -> pay(f, true, "race-money"));
        Supplier<Object> rejection = () -> lifecycle.reject(f.orderId());
        Race result = overlap(f, paymentFirst ? payment : rejection, paymentFirst ? rejection : payment);
        assertThat(result.winner().error()).isNull();
        if (paymentFirst) {
            assertThat(result.loser().error()).isInstanceOf(InvalidOrderStateException.class);
            assertThat(stock(f)).isEqualTo(7);
            assertFacts(f, "ORDER_PLACED", "ORDER_CONFIRMED");
        } else {
            assertThat(result.loser().error()).isNull();
            assertThat(result.loser().value()).isEqualTo(REFUND_REQUIRED);
            assertThat(stock(f)).isEqualTo(10);
            assertCancelled(f, "REFUND_REQUIRED", "STAFF_REJECTED");
            assertFacts(f, "ORDER_PLACED", "ORDER_REJECTED", "REFUND_REQUIRED");
            assertFact(f, 3, "CANCELLED", "REFUND_REQUIRED", null);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void overlappingPaymentAndCallbackExpiryForceBothWinners(boolean paymentFirst) throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        Instant cutoff = deadline(f);
        Supplier<Object> payment = () -> at(cutoff.minusSeconds(1), () -> pay(f, true, "race-money"));
        Supplier<Object> expiry = () -> at(cutoff, () -> pay(f, false, null));
        Race result = overlap(f, paymentFirst ? payment : expiry, paymentFirst ? expiry : payment);
        assertThat(result.winner().error()).isNull();
        assertThat(result.loser().error()).isNull();
        if (paymentFirst) {
            assertThat(result.loser().value()).isEqualTo(ALREADY_SETTLED);
            assertThat(stock(f)).isEqualTo(7);
            assertFacts(f, "ORDER_PLACED", "ORDER_CONFIRMED");
        } else {
            assertThat(result.loser().value()).isEqualTo(REFUND_REQUIRED);
            assertThat(stock(f)).isEqualTo(10);
            assertFacts(f, "ORDER_PLACED", "ORDER_EXPIRED", "REFUND_REQUIRED");
            assertFact(f, 2, "CANCELLED", "FAILED", "PAYMENT_EXPIRED");
            assertFact(f, 3, "CANCELLED", "REFUND_REQUIRED", null);
        }
        Map<String, Object> before = snapshot(f);
        pay(f, true, "duplicate");
        pay(f, false, null);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @Test
    void verifiedFailureBeforeDeadlineReleasesWithoutPaidAtAndLateMoneyDoesNotRevive() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        assertThat(pay(f, false, null)).isEqualTo(SETTLED);
        assertThat(stock(f)).isEqualTo(10);
        assertCancelled(f, "FAILED", "PAYMENT_FAILED");
        assertThat(jdbc.queryForMap("select paid_at, payment_transaction_id from payments where order_id=?", f.id()).values())
                .containsOnlyNulls();
        assertFacts(f, "ORDER_PLACED", "PAYMENT_FAILED");
        Map<String, Object> before = snapshot(f);
        assertThat(pay(f, false, null)).isEqualTo(ALREADY_SETTLED);
        assertThat(snapshot(f)).isEqualTo(before);
        assertThat(pay(f, true, "late-money")).isEqualTo(REFUND_REQUIRED);
        assertCancelled(f, "REFUND_REQUIRED", "PAYMENT_FAILED");
        assertThat(stock(f)).isEqualTo(10);
        assertFacts(f, "ORDER_PLACED", "PAYMENT_FAILED", "REFUND_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void inactiveLegacyValidUrlIgnoresNullOrProposedDeadline(boolean proposedBatch) {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        jdbc.update("update orders set created_at=clock_timestamp()-interval '2 days', payment_expires_at=? where id=?",
                proposedBatch ? Timestamp.from(clock.now().minusSeconds(86400)) : null, f.id());
        assertThat(withPolicy(new ReservationPolicy(false, true, true), () -> pay(f, true, "valid-old-url"))).isEqualTo(SETTLED);
        assertThat(stock(f)).isEqualTo(7);
        assertFacts(f, "ORDER_PLACED", "ORDER_CONFIRMED");
        assertThat(jdbc.queryForObject("select stock_released_at from orders where id=?", Timestamp.class, f.id())).isNull();
    }

    @Test
    void invalidAmountAndUnknownReferenceHaveZeroMutationEvenWhenDue() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        clock.fixed.set(deadline(f));
        Map<String, Object> before = snapshot(f);
        assertThat(settlement.settle(f.orderId(), BigDecimal.ONE, true, "bad")).isEqualTo(AMOUNT_MISMATCH);
        assertThat(settlement.settle(f.orderId(), BigDecimal.ONE, false, null)).isEqualTo(AMOUNT_MISMATCH);
        assertThat(settlement.settle(f.orderId(), null, false, null)).isEqualTo(AMOUNT_MISMATCH);
        assertThat(settlement.settle("unknown", f.amount(), true, "bad")).isEqualTo(NOT_FOUND);
        assertThat(settlement.settle(f.orderId(), f.amount(), true, " ")).isEqualTo(NOT_FOUND);
        String paymentId = jdbc.queryForObject("select payment_id from payments where order_id=?", String.class, f.id());
        PaymentService adapter = context.getBean(PaymentService.class);
        assertThat(adapter.handleIpn(Map.of("vnp_TxnRef", paymentId, "vnp_Amount", "3001",
                "vnp_ResponseCode", "00", "vnp_TransactionNo", "bad"))).isEqualTo(PaymentService.IpnResult.AMOUNT_MISMATCH);
        assertThat(adapter.handleIpn(Map.of("vnp_TxnRef", f.orderId(), "vnp_Amount", "3000",
                "vnp_ResponseCode", "00", "vnp_TransactionNo", "bad"))).isEqualTo(PaymentService.IpnResult.ORDER_NOT_FOUND);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @Test
    void codCallbackIsNotFoundWithoutMutation() {
        Fixture f = checkout(PaymentMethod.COD);
        Map<String, Object> before = snapshot(f);
        assertThat(pay(f, true, "cod-money")).isEqualTo(NOT_FOUND);
        assertThat(pay(f, false, null)).isEqualTo(NOT_FOUND);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void actualVnpayAdapterResolvesPaymentReferenceAndUsesSharedLifecycle(boolean success) {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        String paymentId = jdbc.queryForObject("select payment_id from payments where order_id=?", String.class, f.id());
        assertThat(paymentId).isNotEqualTo(f.orderId());
        var params = Map.of("vnp_TxnRef", paymentId, "vnp_Amount", "3000",
                "vnp_ResponseCode", success ? "00" : "24", "vnp_TransactionNo", "adapter-money");
        assertThat(context.getBean(PaymentService.class).handleIpn(params))
                .isEqualTo(success ? PaymentService.IpnResult.CONFIRMED : PaymentService.IpnResult.FAILED);
        assertFacts(f, "ORDER_PLACED", success ? "ORDER_CONFIRMED" : "PAYMENT_FAILED");
        assertThat(stock(f)).isEqualTo(success ? 7 : 10);
        if (!success) {
            assertThat(jdbc.queryForMap("select paid_at,payment_transaction_id from payments where order_id=?", f.id()).values()).containsOnlyNulls();
        }
        Map<String, Object> before = snapshot(f);
        assertThat(context.getBean(PaymentService.class).handleIpn(params)).isEqualTo(PaymentService.IpnResult.ALREADY_SETTLED);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @Test
    void actualVnpayLateMoneyAdapterAcknowledgesReviewWithoutReviving() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        clock.fixed.set(deadline(f));
        String paymentId = jdbc.queryForObject("select payment_id from payments where order_id=?", String.class, f.id());
        assertThat(context.getBean(PaymentService.class).handleIpn(Map.of("vnp_TxnRef", paymentId,
                "vnp_Amount", "3000", "vnp_ResponseCode", "00", "vnp_TransactionNo", "adapter-late-money")))
                .isEqualTo(PaymentService.IpnResult.REFUND_REQUIRED);
        assertCancelled(f, "REFUND_REQUIRED", "PAYMENT_EXPIRED");
        assertThat(stock(f)).isEqualTo(10);
        assertFacts(f, "ORDER_PLACED", "ORDER_EXPIRED", "REFUND_REQUIRED");
    }

    @Test
    void actualSignedMomoCallbackUsesSharedDeadlineEngine() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        clock.fixed.set(deadline(f));
        MomoProperties properties = new MomoProperties();
        properties.setAccessKey("test-access"); properties.setSecretKey("test-only-secret");
        MomoSignatureService signature = new MomoSignatureService(properties);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", f.orderId()); payload.put("amount", "30");
        payload.put("resultCode", "0"); payload.put("transId", "momo-money");
        payload.put("signature", signature.sign("accessKey=test-access&amount=30&extraData=&message=&orderId=" + f.orderId()
                + "&orderInfo=&orderType=&partnerCode=&payType=&requestId=&responseTime=&resultCode=0&transId=momo-money"));
        var controller = new MomoWebhookController(signature, properties, settlement);
        assertThat(controller.handleWebhook(payload).getStatusCode().value()).isEqualTo(200);
        assertCancelled(f, "REFUND_REQUIRED", "PAYMENT_EXPIRED");
        assertThat(stock(f)).isEqualTo(10);
        assertFacts(f, "ORDER_PLACED", "ORDER_EXPIRED", "REFUND_REQUIRED");
        Map<String, Object> before = snapshot(f);
        controller.handleWebhook(payload);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @Test
    void activatedNullDeadlineRequiresReviewButConfirmedDuplicateRemainsIdempotent() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        jdbc.update("update orders set payment_expires_at=null where id=?", f.id());
        assertReviewWithoutWrites(f);
        jdbc.update("update orders set order_status='CONFIRMED' where id=?", f.id());
        jdbc.update("update payments set payment_status='CONFIRMED', paid_at=clock_timestamp(), payment_transaction_id='known' where order_id=?", f.id());
        Map<String, Object> before = snapshot(f);
        assertThat(pay(f, true, "different")).isEqualTo(ALREADY_SETTLED);
        assertThat(pay(f, false, null)).isEqualTo(ALREADY_SETTLED);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @Test
    void conflictingStoredTotalsRequireReviewEvenWhenCallbackMatchesPaymentAmount() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        jdbc.update("update payments set payment_amount=31 where order_id=?", f.id());
        Map<String, Object> before = snapshot(f);
        for (boolean success : List.of(true, false)) {
            assertThatThrownBy(() -> settlement.settle(f.orderId(), new BigDecimal("31"), success, "conflicting-money"))
                    .satisfies(error -> assertThat(error.getClass().getSimpleName()).isEqualTo("ReservationReviewRequiredException"));
            assertThat(snapshot(f)).isEqualTo(before);
        }
    }

    @Test
    void missingPaymentOrItemsAndAmbiguousLegacyPairsRequireReviewWithoutRepair() {
        for (String mutation : List.of(
                "delete from payments where order_id=?",
                "delete from order_items where order_id=?",
                "update orders set order_status='CANCELLED' where id=?",
                "update orders set order_status='CONFIRMED' where id=?",
                "update orders set order_status='COMPLETED' where id=?",
                "update payments set payment_status='FAILED' where order_id=?")) {
            Fixture f = checkout(PaymentMethod.E_WALLET);
            jdbc.update(mutation, f.id());
            assertReviewWithoutWrites(f);
            withPolicy(new ReservationPolicy(false, false, false), () -> {
                assertReviewWithoutWrites(f); return null;
            });
        }
    }

    @Test
    void hiddenHistoricalVariantReleasesNativelyAndStaysHidden() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        jdbc.update("update variants set is_deleted=true where id=?", f.variant());
        assertThat(pay(f, false, null)).isEqualTo(SETTLED);
        assertThat(stock(f)).isEqualTo(10);
        assertThat(context.getBean(VariantRepository.class).findById(f.variant())).isEmpty();
        assertThat(jdbc.queryForObject("select is_deleted from variants where id=?", Boolean.class, f.variant())).isTrue();
    }

    @Test
    void laterVariantOverflowRollsBackEarlierReleaseAndAllFacts() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        long second = variant(Integer.MAX_VALUE);
        jdbc.update("insert into order_items(order_id,variant_id,quantity,unit_price) values (?,?,1,0)", f.id(), second);
        Map<String, Object> before = snapshot(f);
        clock.fixed.set(deadline(f));
        assertThatThrownBy(() -> pay(f, true, "rollback-money")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(snapshot(f)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select variant_stock from variants where id=?", Integer.class, second)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void missingHistoricalTargetRollsBackWholeCallback() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        transaction.executeWithoutResult(status -> {
            jdbc.execute("set local session_replication_role = replica");
            jdbc.update("delete from variants where id=?", f.variant());
        });
        Map<String, Object> before = snapshot(f);
        assertThatThrownBy(() -> pay(f, false, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock target not found");
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @Test
    void secondFactFailureRollsBackReleaseMoneyAndFirstExpiryFact() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        Map<String, Object> before = snapshot(f);
        clock.fixed.set(deadline(f));
        jdbc.execute("alter table event_outbox add constraint task6_fail_refund check (event_type <> 'REFUND_REQUIRED')");
        try {
            assertThatThrownBy(() -> pay(f, true, "atomic-money")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(snapshot(f)).isEqualTo(before);
        } finally { jdbc.execute("alter table event_outbox drop constraint task6_fail_refund"); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders", "payments"})
    void heldRowExhaustsTwoSecondSettlementBoundWithoutMutation(String table) throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        Map<String, Object> before = snapshot(f);
        try (Connection holder = connect(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from " + table
                    + (table.equals("orders") ? " where id=? for update" : " where order_id=? for update"))) {
                lock.setLong(1, f.id()); lock.executeQuery().close();
            }
            long started = System.nanoTime();
            Future<Outcome> waiter = executor.submit(() -> attempt(() -> pay(f, true, "bounded-money")));
            try {
                Outcome outcome = waiter.get(8, TimeUnit.SECONDS);
                assertThat(outcome.error()).isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(1500L, 5000L);
                assertThat(snapshot(f)).isEqualTo(before);
            } finally { holder.rollback(); }
        }
    }

    @Test
    void paymentLockIsAcquiredOnlyAfterOrderAndClockSamplesAfterPaymentLock() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        try (Connection holder = connect(); Connection probe = connect();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from payments where order_id=? for update")) {
                lock.setLong(1, f.id());
                lock.executeQuery().close();
            }
            AtomicInteger waiterPid = new AtomicInteger();
            Future<Outcome> waiter = executor.submit(() -> attempt(() -> transaction.execute(status -> {
                waiterPid.set(pid());
                return pay(f, true, "post-lock-money");
            })));
            try {
                String waitingQuery = awaitBlocked(holder, backendPid(holder), waiterPid, waiter);
                assertThat(waitingQuery).containsIgnoringCase("payments");
                assertThat(clock.samples.get()).isZero();
                // The callback holds the order lock while blocked on payment.
                try (var statement = probe.createStatement()) {
                    assertThatThrownBy(() -> statement.executeQuery("select id from orders where id=" + f.id() + " for update nowait"))
                            .isInstanceOf(SQLException.class);
                }
                assertThat(waiter.get(8, TimeUnit.SECONDS).error())
                        .isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
                assertThat(clock.samples.get()).isZero();
            } finally { holder.rollback(); }
        }
        assertThat(pay(f, true, "post-lock-money")).isEqualTo(SETTLED);
        assertThat(clock.samples.get()).isEqualTo(1);
    }

    @Test
    void settlementLockBoundIsTransactionLocalAndResetsOnSameBackend() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        try (Connection a = pool.getConnection(); Connection b = pool.getConnection();
             Connection c = pool.getConnection(); Connection d = pool.getConnection()) {
            int used = transaction.execute(status -> {
                assertThat(entityManager.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo("0");
                pay(f, true, "money");
                assertThat(entityManager.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo("2s");
                return pid();
            });
            transaction.executeWithoutResult(status -> {
                assertThat(pid()).isEqualTo(used);
                assertThat(entityManager.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo("0");
            });
        }
    }

    private static void assertReviewWithoutWrites(Fixture f) {
        Map<String, Object> before = snapshot(f);
        assertThatThrownBy(() -> pay(f, true, "review-money"))
                .satisfies(error -> assertThat(error.getClass().getSimpleName()).isEqualTo("ReservationReviewRequiredException"));
        assertThat(snapshot(f)).isEqualTo(before);
        assertThatThrownBy(() -> pay(f, false, null))
                .satisfies(error -> assertThat(error.getClass().getSimpleName()).isEqualTo("ReservationReviewRequiredException"));
        assertThat(snapshot(f)).isEqualTo(before);
    }

    private static <T> T withPolicy(ReservationPolicy policy, Supplier<T> action) {
        Object target = AopTestUtils.getTargetObject(lifecycle);
        ReflectionTestUtils.setField(target, "reservationPolicy", policy);
        try { return action.get(); }
        finally { ReflectionTestUtils.setField(target, "reservationPolicy", originalPolicy); }
    }

    private static <T> T at(Instant instant, Supplier<T> action) {
        clock.fixed.set(instant);
        try { return action.get(); } finally { clock.fixed.remove(); }
    }

    private static PaymentSettlementService.Result pay(Fixture f, boolean success, String reference) {
        return settlement.settle(f.orderId(), f.amount(), success, reference);
    }

    private static Race overlap(Fixture f, Supplier<Object> winner, Supplier<Object> loser) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger winnerPid = new AtomicInteger();
        AtomicInteger loserPid = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2); Connection observer = connect()) {
            Future<Outcome> first = executor.submit(() -> attempt(() -> transaction.execute(status -> {
                context.getBean(OrderRepository.class).findWithLockByOrderId(f.orderId()).orElseThrow();
                winnerPid.set(pid());
                Object result = winner.get();
                locked.countDown();
                try { assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return result;
            })));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Outcome> second = executor.submit(() -> attempt(() -> transaction.execute(status -> {
                loserPid.set(pid());
                return loser.get();
            })));
            try {
                assertThat(awaitBlocked(observer, winnerPid.get(), loserPid, second)).containsIgnoringCase("orders");
            } finally { proceed.countDown(); }
            return new Race(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }

    private static String awaitBlocked(Connection observer, int holder, AtomicInteger waiter, Future<?> future) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            try (var refresh = observer.createStatement()) { refresh.execute("select pg_stat_clear_snapshot()"); }
            try (PreparedStatement query = observer.prepareStatement("select query from pg_stat_activity where pid=? "
                    + "and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid))")) {
                query.setInt(1, waiter.get());
                query.setInt(2, holder);
                try (ResultSet rows = query.executeQuery()) { if (rows.next()) return rows.getString(1); }
            }
            assertThat(future.isDone()).as("waiter must overlap the held transaction").isFalse();
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
    private static Instant deadline(Fixture f) { return jdbc.queryForObject("select payment_expires_at from orders where id=?", Timestamp.class, f.id()).toInstant(); }
    private static int stock(Fixture f) { return jdbc.queryForObject("select variant_stock from variants where id=?", Integer.class, f.variant()); }
    private static Map<String, Object> snapshot(Fixture f) {
        return Map.of("order", jdbc.queryForMap("select * from orders where id=?", f.id()),
                "payments", jdbc.queryForList("select * from payments where order_id=?", f.id()),
                "stock", jdbc.queryForList("select id,variant_stock,is_deleted from variants order by id"),
                "events", jdbc.queryForList("select * from event_outbox where order_id=? order by order_sequence", f.id()));
    }
    private static void assertCancelled(Fixture f, String payment, String reason) {
        assertThat(jdbc.queryForObject("select order_status from orders where id=?", String.class, f.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select payment_status from payments where order_id=?", String.class, f.id())).isEqualTo(payment);
        assertThat(jdbc.queryForObject("select cancellation_reason from orders where id=?", String.class, f.id())).isEqualTo(reason);
        assertThat(jdbc.queryForObject("select stock_released_at from orders where id=?", Timestamp.class, f.id())).isNotNull();
    }
    private static void assertFacts(Fixture f, String... types) {
        assertThat(jdbc.queryForList("select event_type from event_outbox where order_id=? order by order_sequence", String.class, f.id())).containsExactly(types);
        assertThat(jdbc.queryForObject("select event_sequence from orders where id=?", Long.class, f.id())).isEqualTo((long) types.length);
        for (int i = 0; i < types.length; i++) {
            var node = mapper.readTree(jdbc.queryForObject("select payload::text from event_outbox where order_id=? and order_sequence=?", String.class, f.id(), i + 1));
            assertThat(node.get("orderSequence").asLong()).isEqualTo(i + 1L);
            assertThat(node.get("eventType").asString()).isEqualTo(types[i]);
            assertThat(node.get("orderId").asString()).isEqualTo(f.orderId());
        }
    }
    private static void assertFact(Fixture f, long sequence, String order, String payment, String reason) {
        var node = mapper.readTree(jdbc.queryForObject("select payload::text from event_outbox where order_id=? and order_sequence=?", String.class, f.id(), sequence));
        assertThat(node.get("orderStatus").asString()).isEqualTo(order);
        assertThat(node.get("paymentStatus").asString()).isEqualTo(payment);
        assertThat(node.get("paymentMethod").asString()).isEqualTo("E_WALLET");
        assertThat(node.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(node.get("eventId").asString())).isNotNull();
        assertThat(node.get("customerUserId").asString()).isEqualTo(jdbc.queryForObject(
                "select c.user_id::text from orders o join customers c on c.id=o.customer_id where o.id=?", String.class, f.id()));
        assertThat(new BigDecimal(node.get("total").asString())).isEqualByComparingTo(f.amount());
        assertThat(node.get("entryPoint").asString()).isEqualTo("PAYMENT_CALLBACK");
        assertThat(node.get("reason").isNull() ? null : node.get("reason").asString()).isEqualTo(reason);
        if (clock.fixed.get() != null) assertThat(Instant.parse(node.get("occurredAt").asString())).isEqualTo(clock.fixed.get());
    }

    private static Fixture checkout(PaymentMethod method) {
        String suffix = UUID.randomUUID().toString();
        long role = jdbc.queryForObject("insert into roles(role_id,role_code,role_name) values (?,?,'Fixture') returning id", Long.class, "R" + suffix, suffix);
        long user = jdbc.queryForObject("insert into users(user_id,user_email,user_password_hash,role_id) values (?,?,'unused',?) returning id", Long.class, "U" + suffix, suffix + "@example.invalid", role);
        long customer = jdbc.queryForObject("insert into customers(customer_id,user_id,customer_name,customer_phone) values (?,?,'Fixture','000') returning id", Long.class, "C" + suffix, user);
        long variant = variant(10);
        long cart = jdbc.queryForObject("insert into carts(cart_id,customer_id) values (?,?) returning id", Long.class, "CRT" + suffix, customer);
        jdbc.update("insert into cart_items(cart_item_id,cart_id,variant_id,quantity) values (?,?,?,3)", "CI" + suffix, cart, variant);
        assertThat(jdbc.queryForObject("select variant_stock from variants where id=?", Integer.class, variant)).isEqualTo(10);
        var response = context.getBean(CartService.class).checkout(customer, new CartCheckoutRequest("fixture", "Fixture", "000", method));
        long id = jdbc.queryForObject("select id from orders where order_id=?", Long.class, response.orderId());
        clock.samples.set(0);
        return new Fixture(id, response.orderId(), variant, new BigDecimal("30.00"));
    }
    private static long variant(int stock) {
        String suffix = UUID.randomUUID().toString();
        long product = jdbc.queryForObject("insert into products(product_id,product_name,product_description,product_status,product_slug) values (?,'Fixture','fixture','ACTIVE',?) returning id", Long.class, "P" + suffix, suffix);
        return jdbc.queryForObject("insert into variants(variant_id,product_id,variant_sku,variant_price,variant_stock) values (?,?,?,10,?) returning id", Long.class, "V" + suffix, product, suffix, stock);
    }

    private record Fixture(long id, String orderId, long variant, BigDecimal amount) { }
    private record Outcome(Object value, RuntimeException error) { }
    private record Race(Outcome winner, Outcome loser) { }

    /** Freezes only when explicitly set, using a persisted PostgreSQL deadline as the source. */
    static class ControlledClock extends DatabaseClock {
        final ThreadLocal<Instant> fixed = new ThreadLocal<>();
        final AtomicInteger samples = new AtomicInteger();
        ControlledClock(JdbcTemplate jdbc) { super(jdbc); }
        @Override public Instant now() {
            samples.incrementAndGet();
            return fixed.get() == null ? super.now() : fixed.get();
        }
    }

    @Configuration
    @Import({CheckoutInventoryPostgresTest.Config.class, PaymentSettlementService.class})
    static class Config {
        @Bean @Primary ControlledClock controlledClock(JdbcTemplate jdbc) { return new ControlledClock(jdbc); }
        @Bean PaymentService paymentService(PaymentRepository payments, OrderRepository orders,
                                           PaymentMapper mapper, PaymentSettlementService settlement,
                                           ReservationPolicy policy, DatabaseClock clock,
                                           com.project.dacn.repository.order.OrderItemRepository items) {
            VnpayProperties properties = new VnpayProperties();
            return new PaymentService(payments, orders,
                    new VnpayUrlBuilder(properties, new VnpaySignatureService(properties)), mapper, settlement,
                    policy, clock, items);
        }
    }
}
