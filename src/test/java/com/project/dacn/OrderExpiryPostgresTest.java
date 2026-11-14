package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.order.OrderExpiryWorker;
import com.project.dacn.service.order.OrderLifecycleService;
import com.project.dacn.service.order.ReservationPolicy;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.project.dacn.support.ExpiryJdbcProfile;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real scheduled SKIP LOCKED claims, per-order transactions and PostgreSQL clock/lock behavior. */
class OrderExpiryPostgresTest {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderExpiryPostgresTest.class);

    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static HikariDataSource pool;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static EntityManager entityManager;
    private static ControlledClock clock;
    private static OrderLifecycleService lifecycle;
    private static PaymentSettlementService settlement;
    private static ReservationPolicy originalPolicy;
    private static SimpleMeterRegistry registry;
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
            log.info("Verified Task9 scratch identity: {}", ownedName);
            database.migrate();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(database.jdbcUrl());
            config.setUsername(database.username());
            config.setPassword(database.password());
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            pool = new HikariDataSource(config);
            context = newContext(pool);
            jdbc = context.getBean(JdbcTemplate.class);
            transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            entityManager = SharedEntityManagerCreator.createSharedEntityManager(
                    context.getBean(EntityManagerFactory.class));
            clock = context.getBean(ControlledClock.class);
            lifecycle = context.getBean(OrderLifecycleService.class);
            settlement = context.getBean(PaymentSettlementService.class);
            originalPolicy = context.getBean(ReservationPolicy.class);
            mapper = context.getBean(ObjectMapper.class);
            assertThat(jdbc.queryForObject("""
                    select count(*) from flyway_schema_history
                     where version = '34' and success
                    """, Long.class)).isEqualTo(1);
        } catch (Exception | AssertionError failure) {
            cleanup();
            throw failure;
        }
    }

    @BeforeEach
    void clean() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(ownedName);
        jdbc.execute("alter table event_outbox drop constraint if exists task9_fail_expired");
        jdbc.execute("truncate event_outbox, realtime_signal_outbox, reviews, order_items, payments, orders, "
                + "cart_items, carts, variant_attribute_values, variants, product_attribute_values, "
                + "products, customers, users, roles restart identity cascade");
        clock.fixed.remove();
        clock.samples.set(0);
        registry = new SimpleMeterRegistry();
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
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isFalse();
                }
            }
            log.info("Confirmed Task9 scratch cleanup: {}", ownedName);
            database = null;
        }
    }

    @Test
    void expiringExactlyAtThePersistedDeadlineIsExpiredOnce() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlineNow(f);
        Instant deadline = deadline(f);
        clock.fixed.set(deadline);

        assertThat(lifecycle.expireNextDue()).isTrue();

        assertExpired(f, deadline, 10);
        assertThat(deadline(f)).isEqualTo(deadline);
        clock.fixed.remove();
        assertThat(lifecycle.expireNextDue()).isFalse();
        assertFacts(f, "ORDER_PLACED", "ORDER_EXPIRED");
    }

    @Test
    void futureDeadlineIsNeverClaimed() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        Instant deadline = deadline(f);

        assertThat(lifecycle.expireNextDue()).isFalse();
        worker().sweep();

        assertPendingUnchanged(f);
        assertThat(deadline(f)).isEqualTo(deadline);
        assertThat(claims()).isZero();
    }

    @Test
    void inactiveProposedLegacyDeadlineAndInactiveLifecycleMakeNoClaim() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        ReservationPolicy inactive = new ReservationPolicy(false, false, false);

        withPolicy(inactive, () -> {
            assertThat(lifecycle.expireNextDue()).isFalse();
            new OrderExpiryWorker(lifecycle, inactive, registry).sweep();
            return null;
        });

        assertPendingUnchanged(f);
        assertThat(claims()).isZero();
        assertThat(failures()).isZero();
    }

    @Test
    void codNullQuarantinedPaidTerminalAndMissingPaymentRowsAreExcluded() {
        List<Fixture> excluded = new ArrayList<>();

        Fixture cod = checkout(PaymentMethod.COD);
        setDeadlinePast(cod);
        excluded.add(cod);

        Fixture nullDeadline = checkout(PaymentMethod.E_WALLET);
        jdbc.update("update orders set payment_expires_at=null where id=?", nullDeadline.id());
        excluded.add(nullDeadline);

        Fixture cancelled = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(cancelled);
        jdbc.update("update orders set order_status='CANCELLED' where id=?", cancelled.id());
        excluded.add(cancelled);

        Fixture completed = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(completed);
        jdbc.update("update orders set order_status='COMPLETED' where id=?", completed.id());
        excluded.add(completed);

        Fixture paid = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(paid);
        jdbc.update("""
                update payments set payment_status='CONFIRMED', paid_at=clock_timestamp(),
                       payment_transaction_id=?
                 where order_id=?
                """, "paid-" + paid.orderId(), paid.id());
        excluded.add(paid);

        Fixture paidEvidencePending = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(paidEvidencePending);
        jdbc.update("""
                update payments set paid_at=clock_timestamp(), payment_transaction_id=?
                 where order_id=?
                """, "pending-evidence-" + paidEvidencePending.orderId(), paidEvidencePending.id());
        excluded.add(paidEvidencePending);

        Fixture confirmedUnpaid = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(confirmedUnpaid);
        jdbc.update("update orders set order_status='CONFIRMED' where id=?", confirmedUnpaid.id());
        excluded.add(confirmedUnpaid);

        Fixture missingPayment = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(missingPayment);
        jdbc.update("delete from payments where order_id=?", missingPayment.id());
        excluded.add(missingPayment);

        Fixture released = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(released);
        jdbc.update("update orders set stock_released_at=clock_timestamp() where id=?", released.id());
        excluded.add(released);

        Fixture refund = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(refund);
        jdbc.update("update payments set payment_status='REFUND_REQUIRED' where order_id=?", refund.id());
        excluded.add(refund);

        Fixture failed = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(failed);
        jdbc.update("update payments set payment_status='FAILED' where order_id=?", failed.id());
        excluded.add(failed);

        List<Map<String, Object>> before = excluded.stream().map(OrderExpiryPostgresTest::snapshot).toList();
        worker().sweep();

        assertThat(claims()).isZero();
        assertThat(failures()).isZero();
        for (int i = 0; i < excluded.size(); i++) {
            assertThat(snapshot(excluded.get(i))).isEqualTo(before.get(i));
        }

        Fixture eligible = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(eligible);
        worker().sweep();
        assertExpired(eligible, null, 10);
    }

    @Test
    void paidOrderAfterAStaleDueScanIsNeverExpired() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlineNow(f);
        Instant deadline = deadline(f);
        Long staleCandidate = jdbc.queryForObject("""
                select id from orders
                 where id=? and payment_expires_at <= clock_timestamp()
                   and order_status='PENDING' and stock_released_at is null
                """, Long.class, f.id());
        assertThat(staleCandidate).isEqualTo(f.id());

        clock.fixed.set(deadline.minusNanos(1_000));
        assertThat(pay(f, true, "winner-before-cutoff")).isEqualTo(PaymentSettlementService.Result.SETTLED);
        clock.fixed.remove();

        assertThat(lifecycle.expireNextDue()).isFalse();
        worker().sweep();

        assertThat(orderStatus(f.id())).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paymentStatus(f.id())).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(stock(f)).isEqualTo(7);
        assertFacts(f, "ORDER_PLACED", "ORDER_CONFIRMED");
        assertThat(releaseMarker(f.id())).isNull();
        assertThat(claims()).isZero();
    }

    @Test
    void oneTickClaimsAtMostFiftyOrders() {
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Fixture f = checkout(PaymentMethod.E_WALLET);
            setDeadlinePast(f);
            fixtures.add(f);
        }

        worker().sweep();

        assertThat(cancelledCount()).isEqualTo(50);
        assertThat(claims()).isEqualTo(50);
        assertThat(failures()).isZero();

        worker().sweep();
        assertThat(cancelledCount()).isEqualTo(55);
        assertThat(claims()).isEqualTo(55);
    }

    @Test
    void twoWorkersExpireEveryDueOrderExactlyOnce() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Fixture f = checkout(PaymentMethod.E_WALLET);
            setDeadlinePast(f);
            fixtures.add(f);
        }
        OrderExpiryWorker first = new OrderExpiryWorker(lifecycle, activePolicy(), registry);
        OrderExpiryWorker second = new OrderExpiryWorker(lifecycle, activePolicy(), registry);

        try (Connection one = connect(); Connection two = connect(); Connection observer = connect();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            one.setAutoCommit(false);
            two.setAutoCommit(false);
            lockRow(one, "payments", paymentPk(fixtures.get(0).id()));
            lockRow(two, "payments", paymentPk(fixtures.get(1).id()));
            Future<?> firstTick = executor.submit(first::sweep);
            Future<?> secondTick = executor.submit(second::sweep);
            try {
                assertThat(awaitBlocked(observer, backendPid(one), firstTick))
                        .containsIgnoringCase("payments");
                assertThat(awaitBlocked(observer, backendPid(two), secondTick))
                        .containsIgnoringCase("payments");
            } finally {
                one.rollback();
                two.rollback();
            }
            firstTick.get(60, TimeUnit.SECONDS);
            secondTick.get(60, TimeUnit.SECONDS);
        }

        for (Fixture f : fixtures) {
            assertExpired(f, null, 10);
        }
        assertThat(claims()).isEqualTo(20);
        assertThat(failures()).isZero();
    }

    @Test
    void lockedCandidateIsSkippedWithoutWaitingAndProcessedAfterRelease() throws Exception {
        Fixture locked = checkout(PaymentMethod.E_WALLET);
        Fixture other = checkout(PaymentMethod.E_WALLET);
        Instant sameDeadline = clock.now().minusSeconds(30);
        setDeadline(locked, sameDeadline);
        setDeadline(other, sameDeadline);

        try (Connection holder = connect()) {
            holder.setAutoCommit(false);
            lockRow(holder, "orders", locked.id());
            worker().sweep();
            assertThat(claims()).isEqualTo(1);
            assertThat(failures()).isZero();
            assertPendingUnchanged(locked);
            assertExpired(other, null, 10);
            holder.rollback();
        }

        worker().sweep();
        assertExpired(locked, null, 10);
    }

    @Test
    void paymentContentionExhaustsTheTwoSecondBoundAndRollsBackTheClaim() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        Map<String, Object> before = snapshot(f);
        try (Connection holder = connect(); Connection observer = connect();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            lockRow(holder, "payments", paymentPk(f.id()));
            long started = System.nanoTime();
            Future<Outcome> future = executor.submit(() -> attempt(() -> {
                worker().sweep();
                return null;
            }));
            String waiting = awaitBlocked(observer, backendPid(holder), future);
            assertThat(waiting).containsIgnoringCase("payments");
            Outcome outcome = future.get(8, TimeUnit.SECONDS);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(outcome.error()).isNull();
            assertThat(elapsed).isBetween(1500L, 5000L);
            assertThat(snapshot(f)).isEqualTo(before);
            assertThat(claims()).isZero();
            assertThat(failures()).isEqualTo(1);
            holder.rollback();
        }

        worker().sweep();
        assertExpired(f, null, 10);
        assertThat(claims()).isEqualTo(1);
    }

    @Test
    void missingHistoricalTargetRollsBackTheWholeScheduledExpiry() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        transaction.executeWithoutResult(status -> {
            jdbc.execute("set local session_replication_role = replica");
            jdbc.update("delete from variants where id=?", f.variant());
        });
        Map<String, Object> before = snapshot(f);

        worker().sweep();

        assertThat(snapshot(f)).isEqualTo(before);
        assertPendingUnchanged(f);
        assertThat(claims()).isZero();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void integerOverflowRollsBackTheWholeScheduledExpiry() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        Fixture later = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        jdbc.update("insert into order_items(order_id,variant_id,quantity,unit_price) values (?,?,3,10)",
                f.id(), later.variant());
        jdbc.update("update orders set order_total=60 where id=?", f.id());
        jdbc.update("update payments set payment_amount=60 where order_id=?", f.id());
        jdbc.update("update variants set variant_stock=? where id=?", Integer.MAX_VALUE, later.variant());
        Map<String, Object> before = snapshot(f);

        worker().sweep();

        assertThat(snapshot(f)).isEqualTo(before);
        assertPendingUnchanged(f);
        assertThat(stock(f)).isEqualTo(7);
        assertThat(stock(later)).isEqualTo(Integer.MAX_VALUE);
        assertThat(claims()).isZero();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void scheduledReleaseRestoresHiddenHistoricalVariantsWithoutUnhidingThem() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        jdbc.update("update variants set is_deleted=true where id=?", f.variant());
        worker().sweep();
        assertExpired(f, null, 10);
        assertThat(jdbc.queryForObject("select is_deleted from variants where id=?",
                Boolean.class, f.variant())).isTrue();
    }

    @Test
    void outboxFailureRollsBackStockMarkerStateSequenceAndFactsThenRecovers() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        Map<String, Object> before = snapshot(f);
        jdbc.execute("alter table event_outbox add constraint task9_fail_expired "
                + "check (event_type <> 'ORDER_EXPIRED')");
        try {
            worker().sweep();

            assertThat(snapshot(f)).isEqualTo(before);
            assertPendingUnchanged(f);
            assertThat(stock(f)).isEqualTo(7);
            assertThat(claims()).isZero();
            assertThat(failures()).isEqualTo(1);
        } finally {
            jdbc.execute("alter table event_outbox drop constraint task9_fail_expired");
        }

        worker().sweep();
        assertExpired(f, null, 10);
        assertThat(claims()).isEqualTo(1);
    }

    @Test
    void sweepPauseMakesZeroClaimsAndResumeExpiresTheOriginalDeadlineOnce() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        Instant originalDeadline = deadline(f);
        ReservationPolicy paused = new ReservationPolicy(true, true, true);

        withPolicy(paused, () -> {
            new OrderExpiryWorker(lifecycle, paused, registry).sweep();
            assertThat(lifecycle.expireNextDue()).isFalse();
            return null;
        });

        assertPendingUnchanged(f);
        assertThat(deadline(f)).isEqualTo(originalDeadline);
        assertThat(claims()).isZero();

        withPolicy(activePolicy(), () -> {
            new OrderExpiryWorker(lifecycle, activePolicy(), registry).sweep();
            return null;
        });

        assertExpired(f, null, 10);
        assertThat(deadline(f)).isEqualTo(originalDeadline);
        assertThat(claims()).isEqualTo(1);

        worker().sweep();
        assertFacts(f, "ORDER_PLACED", "ORDER_EXPIRED");
        assertThat(claims()).isEqualTo(1);
    }

    @Test
    void onlinePauseAloneDoesNotStopSweeping() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        ReservationPolicy onlinePaused = new ReservationPolicy(true, true, false);

        withPolicy(onlinePaused, () -> {
            new OrderExpiryWorker(lifecycle, onlinePaused, registry).sweep();
            return null;
        });

        assertExpired(f, null, 10);
        assertThat(claims()).isEqualTo(1);
    }

    @Test
    void sweepPauseDoesNotStopCallbackDrivenExpiryAtTheStoredDeadline() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlineNow(f);
        Instant deadline = deadline(f);
        ReservationPolicy paused = new ReservationPolicy(true, true, true);

        withPolicy(paused, () -> {
            clock.fixed.set(deadline);
            assertThat(pay(f, true, "paused-callback-money"))
                    .isEqualTo(PaymentSettlementService.Result.REFUND_REQUIRED);
            return null;
        });
        clock.fixed.remove();

        assertThat(orderStatus(f.id())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentStatus(f.id())).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        assertThat(cancellationReason(f.id())).isEqualTo("PAYMENT_EXPIRED");
        assertThat(stock(f)).isEqualTo(10);
        assertThat(deadline(f)).isEqualTo(deadline);
        assertFacts(f, "ORDER_PLACED", "ORDER_EXPIRED", "REFUND_REQUIRED");
    }

    @Test
    void paidFirstOverlapSurvivesTheScheduledClaim() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlineNow(f);
        Instant deadline = deadline(f);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection probe = connect()) {
            Future<Outcome> callback = executor.submit(() -> attempt(() -> transaction.execute(status -> {
                clock.fixed.set(deadline.minusNanos(1_000));
                var result = pay(f, true, "paid-first");
                locked.countDown();
                try {
                    assertThat(commit.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return result;
            })));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> {
                    try (var statement = probe.createStatement()) {
                        statement.executeQuery("select id from orders where id=" + f.id() + " for update nowait");
                    }
                }).isInstanceOf(SQLException.class);
                worker().sweep();
                assertThat(claims()).isZero();
            } finally {
                commit.countDown();
            }
            assertThat(callback.get(15, TimeUnit.SECONDS).value())
                    .isEqualTo(PaymentSettlementService.Result.SETTLED);
        }
        clock.fixed.remove();

        worker().sweep();
        assertThat(orderStatus(f.id())).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paymentStatus(f.id())).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(stock(f)).isEqualTo(7);
        assertFacts(f, "ORDER_PLACED", "ORDER_CONFIRMED");
    }

    @Test
    void expiryFirstOverlapMakesTheLateCallbackRefundRequired() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlineNow(f);
        Instant deadline = deadline(f);
        CountDownLatch expired = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger expiryPid = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2);
             Connection observer = connect()) {
            Future<Outcome> expiry = executor.submit(() -> attempt(() -> transaction.execute(status -> {
                clock.fixed.set(deadline);
                boolean result = lifecycle.expireNextDue();
                expiryPid.set(pid());
                expired.countDown();
                try {
                    assertThat(commit.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return result;
            })));
            assertThat(expired.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Outcome> callback = executor.submit(() -> attempt(() -> transaction.execute(status -> {
                clock.fixed.set(deadline.plusSeconds(1));
                return pay(f, true, "late-money");
            })));
            try {
                String waiting = awaitBlocked(observer, expiryPid.get(), callback);
                assertThat(waiting).containsIgnoringCase("orders");
            } finally {
                commit.countDown();
            }
            assertThat(expiry.get(15, TimeUnit.SECONDS).value()).isEqualTo(true);
            assertThat(callback.get(15, TimeUnit.SECONDS).value())
                    .isEqualTo(PaymentSettlementService.Result.REFUND_REQUIRED);
        }

        assertThat(orderStatus(f.id())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentStatus(f.id())).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        assertThat(cancellationReason(f.id())).isEqualTo("PAYMENT_EXPIRED");
        assertThat(stock(f)).isEqualTo(10);
        assertThat(releaseMarker(f.id())).isEqualTo(deadline);
        assertThat(paidAt(f.id())).isEqualTo(deadline.plusSeconds(1));
        assertFacts(f, "ORDER_PLACED", "ORDER_EXPIRED", "REFUND_REQUIRED");
    }

    @Test
    void freshContextReplaysWithoutDuplicatesAndProcessesRemainingWork() {
        Fixture first = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(first);
        worker().sweep();
        assertExpired(first, null, 10);
        Instant firstMarker = releaseMarker(first.id());

        Fixture second = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(second);
        try (AnnotationConfigApplicationContext fresh = newContext(pool)) {
            OrderExpiryWorker freshWorker = fresh.getBean(OrderExpiryWorker.class);
            OrderLifecycleService freshLifecycle = fresh.getBean(OrderLifecycleService.class);

            assertThat(freshLifecycle.expireNextDue()).isTrue();
            assertThat(freshLifecycle.expireNextDue()).isFalse();
            freshWorker.sweep();
        }

        assertExpired(second, null, 10);
        assertExpired(first, null, 10);
        assertThat(releaseMarker(first.id())).isEqualTo(firstMarker);
        assertFacts(first, "ORDER_PLACED", "ORDER_EXPIRED");
    }

    @Test
    void cutoffUsesPostLockDatabaseTimeAndNotTheRealClockAtAdmission() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlineNow(f);
        Instant deadline = deadline(f);
        try (Connection holder = connect(); Connection observer = connect();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            lockRow(holder, "payments", paymentPk(f.id()));
            Future<Boolean> future = executor.submit(() -> transaction.execute(status -> {
                clock.fixed.set(deadline.minusNanos(1_000));
                return lifecycle.expireNextDue();
            }));
            String waiting = awaitBlocked(observer, backendPid(holder), future);
            assertThat(waiting).containsIgnoringCase("payments");
            assertThat(clock.samples.get()).isZero();
            holder.rollback();
            assertThat(future.get(8, TimeUnit.SECONDS)).isTrue();
        }

        assertPendingUnchanged(f);
        clock.fixed.set(deadline);
        assertThat(lifecycle.expireNextDue()).isTrue();
        clock.fixed.remove();
        assertExpired(f, deadline, 10);
    }

    @Test
    void receivedMoneyCommittedDuringPaymentWaitSurvivesTheStaleClaim() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        try (Connection holder = connect(); Connection observer = connect();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            lockRow(holder, "payments", paymentPk(f.id()));
            Future<Boolean> claim = executor.submit(() -> lifecycle.expireNextDue());
            try {
                assertThat(awaitBlocked(observer, backendPid(holder), claim)).containsIgnoringCase("payments");
                try (PreparedStatement update = holder.prepareStatement("""
                        update payments set payment_status='CONFIRMED', paid_at=clock_timestamp(),
                               payment_transaction_id='received-during-wait' where order_id=?
                        """)) {
                    update.setLong(1, f.id());
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }
                holder.commit();
                assertThat(claim.get(8, TimeUnit.SECONDS)).isTrue();
            } finally {
                holder.rollback();
            }
        }
        assertThat(paymentStatus(f.id())).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(stock(f)).isEqualTo(7);
        assertThat(releaseMarker(f.id())).isNull();
        assertFacts(f, "ORDER_PLACED");
    }

    @Test
    void variantContentionIsBoundedAndSamplesNoDecisionClockBeforeTheLock() throws Exception {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        Map<String, Object> before = snapshot(f);
        try (Connection holder = connect(); Connection observer = connect();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            lockRow(holder, "variants", f.variant());
            long started = System.nanoTime();
            Future<Outcome> claim = executor.submit(() -> attempt(lifecycle::expireNextDue));
            assertThat(awaitBlocked(observer, backendPid(holder), claim)).containsIgnoringCase("variants");
            assertThat(clock.samples.get()).isZero();
            assertThat(claim.get(8, TimeUnit.SECONDS).error()).isNotNull();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(1500L, 5000L);
            assertThat(snapshot(f)).isEqualTo(before);
            holder.rollback();
        }
        worker().sweep();
        assertExpired(f, null, 10);
    }

    @Test
    void contradictoryTotalsCancellationEvidenceAndMissingLinesRemainQuarantined() {
        Fixture totals = checkout(PaymentMethod.E_WALLET);
        Fixture cancelled = checkout(PaymentMethod.E_WALLET);
        Fixture missingLines = checkout(PaymentMethod.E_WALLET);
        for (Fixture f : List.of(totals, cancelled, missingLines)) setDeadlinePast(f);
        jdbc.update("update payments set payment_amount=31 where order_id=?", totals.id());
        jdbc.update("update orders set cancellation_reason='PAYMENT_FAILED' where id=?", cancelled.id());
        jdbc.update("delete from order_items where order_id=?", missingLines.id());
        List<Map<String, Object>> before = List.of(snapshot(totals), snapshot(cancelled), snapshot(missingLines));
        worker().sweep();
        assertThat(List.of(snapshot(totals), snapshot(cancelled), snapshot(missingLines))).isEqualTo(before);
        assertThat(claims()).isZero();
        assertThat(failures()).isZero();
    }

    private static AnnotationConfigApplicationContext newContext(DataSource source) {
        var fresh = new AnnotationConfigApplicationContext();
        // The harness, not an individual replay context, owns this shared pool.
        fresh.registerBean(DataSource.class, () -> new ExpiryJdbcProfile(source),
                bean -> bean.setDestroyMethodName(""));
        fresh.register(Config.class);
        fresh.refresh();
        return fresh;
    }

    private static ReservationPolicy activePolicy() {
        return new ReservationPolicy(true, false, false);
    }

    @Test
    void failedOldestCandidateDoesNotStarveHealthyOrdersAcrossTicksAndCanRecover() {
        Fixture bad = checkout(PaymentMethod.E_WALLET);
        Fixture first = checkout(PaymentMethod.E_WALLET);
        setDeadline(bad, clock.now().minusSeconds(60));
        setDeadline(first, clock.now().minusSeconds(30));
        jdbc.update("update variants set variant_stock=? where id=?", Integer.MAX_VALUE, bad.variant());
        var badBefore = new java.util.HashMap<>(snapshot(bad));
        badBefore.remove("stock");

        worker().sweep();

        assertExpired(first, null, 10);
        assertThat(snapshot(bad)).containsAllEntriesOf(badBefore);
        assertThat(stock(bad)).isEqualTo(Integer.MAX_VALUE);
        assertThat(claims()).isEqualTo(1);
        assertThat(failures()).isEqualTo(1);

        Fixture second = checkout(PaymentMethod.E_WALLET);
        setDeadline(second, clock.now().minusSeconds(10));
        worker().sweep();
        assertExpired(first, null, 10);
        assertExpired(second, null, 10);
        assertThat(snapshot(bad)).containsAllEntriesOf(badBefore);
        assertThat(stock(bad)).isEqualTo(Integer.MAX_VALUE);
        assertThat(claims()).isEqualTo(2);
        assertThat(failures()).isEqualTo(2);

        jdbc.update("update variants set variant_stock=7 where id=?", bad.variant());
        worker().sweep();
        assertExpired(bad, null, 10);
        worker().sweep();
        assertExpired(first, null, 10);
        assertExpired(second, null, 10);
        assertThat(claims()).isEqualTo(3);
        assertThat(failures()).isEqualTo(2);
    }

    @Test
    void profilesOneRealExpiryClaimWithoutRecordingBindValues() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        ExpiryJdbcProfile.start();
        try {
            assertThat(lifecycle.expireNextDue()).isTrue();
        } finally {
            var operations = ExpiryJdbcProfile.stop();
            log.info("REMOTE_SUPABASE_SCRATCH expiry JDBC operations={}", operations.size());
            operations.forEach(operation -> log.info("EXPIRY_JDBC {}", operation));
        }
        assertExpired(f, null, 10);
    }

    @Test
    void callerManagedSnapshotsAreRefreshedBeforeExpiryDecisionsAndFacts() {
        Fixture f = checkout(PaymentMethod.E_WALLET);
        setDeadlinePast(f);
        transaction.executeWithoutResult(status -> {
            var managed = entityManager.find(com.project.dacn.entity.order.Order.class, f.id());
            assertThat(managed.getTotal()).isEqualByComparingTo("30");
            try (Connection external = connect(); Statement statement = external.createStatement()) {
                statement.executeUpdate("update orders set order_total=31 where id=" + f.id());
                statement.executeUpdate("update payments set payment_amount=31 where order_id=" + f.id());
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            assertThat(lifecycle.expireNextDue()).isTrue();
            assertThat(managed.getTotal()).isEqualByComparingTo("31");
        });
        assertExpired(f, null, 10);
        var fact = mapper.readTree(jdbc.queryForObject("""
                select payload::text from event_outbox where order_id=? and order_sequence=2
                """, String.class, f.id()));
        assertThat(fact.get("total").asString()).isEqualTo("31.00");
    }

    private static OrderExpiryWorker worker() {
        return new OrderExpiryWorker(lifecycle, originalPolicy, registry);
    }

    private static <T> T withPolicy(ReservationPolicy policy, Supplier<T> action) {
        Object target = AopTestUtils.getTargetObject(lifecycle);
        ReflectionTestUtils.setField(target, "reservationPolicy", policy);
        try {
            return action.get();
        } finally {
            ReflectionTestUtils.setField(target, "reservationPolicy", originalPolicy);
        }
    }

    private static void assertExpired(Fixture f, Instant expectedOccurredAt, int expectedStock) {
        assertThat(orderStatus(f.id())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentStatus(f.id())).isEqualTo(PaymentStatus.FAILED);
        assertThat(cancellationReason(f.id())).isEqualTo("PAYMENT_EXPIRED");
        assertThat(stock(f)).isEqualTo(expectedStock);
        assertThat(paidAt(f.id())).isNull();
        assertThat(transactionId(f.id())).isNull();
        assertThat(eventSequence(f.id())).isEqualTo(2L);
        Instant occurredAt = assertFact(f, 2, "CANCELLED", "FAILED", "PAYMENT_EXPIRED");
        assertThat(releaseMarker(f.id())).isEqualTo(occurredAt);
        if (expectedOccurredAt != null) {
            assertThat(occurredAt).isEqualTo(expectedOccurredAt);
        } else {
            assertThat(occurredAt).isAfterOrEqualTo(deadline(f));
        }
        assertFacts(f, "ORDER_PLACED", "ORDER_EXPIRED");
    }

    private static void assertPendingUnchanged(Fixture f) {
        assertThat(orderStatus(f.id())).isEqualTo(OrderStatus.PENDING);
        assertThat(paymentStatus(f.id())).isEqualTo(PaymentStatus.PENDING);
        assertThat(cancellationReason(f.id())).isNull();
        assertThat(releaseMarker(f.id())).isNull();
        assertThat(eventSequence(f.id())).isEqualTo(1L);
        assertFacts(f, "ORDER_PLACED");
    }

    private static Instant assertFact(Fixture f, long sequence, String order,
                                      String payment, String reason) {
        var node = mapper.readTree(jdbc.queryForObject(
                "select payload::text from event_outbox where order_id=? and order_sequence=?",
                String.class, f.id(), sequence));
        assertThat(node.get("orderStatus").asString()).isEqualTo(order);
        assertThat(node.get("paymentStatus").asString()).isEqualTo(payment);
        assertThat(node.get("paymentMethod").asString()).isEqualTo(f.method().name());
        assertThat(node.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(node.get("entryPoint").asString()).isEqualTo("EXPIRY_SWEEP");
        assertThat(node.get("reason").isNull() ? null : node.get("reason").asString())
                .isEqualTo(reason);
        assertThat(UUID.fromString(node.get("eventId").asString())).isNotNull();
        return Instant.parse(node.get("occurredAt").asString());
    }

    private static void assertFacts(Fixture f, String... types) {
        assertThat(jdbc.queryForList(
                "select event_type from event_outbox where order_id=? order by order_sequence",
                String.class, f.id())).containsExactly(types);
        assertThat(eventSequence(f.id())).isEqualTo((long) types.length);
    }

    private static Fixture checkout(PaymentMethod method) {
        return seed(method);
    }

    private static Fixture seed(PaymentMethod method) {
        return seed(OrderStatus.PENDING, method, PaymentStatus.PENDING, 7, 3,
                method == PaymentMethod.COD ? null : Instant.now().plusSeconds(900));
    }

    /**
     * One statement seeds a realistic reserved order, payment, historical item
     * and placement fact. The remote scratch database makes multi-round-trip
     * fixtures prohibitively slow; every persisted value remains native PostgreSQL.
     */
    private static Fixture seed(OrderStatus status, PaymentMethod method, PaymentStatus paymentStatus,
                                int reservedStock, int quantity, Instant deadline) {
        String suffix = UUID.randomUUID().toString();
        String variantId = "VAR_" + suffix;
        String orderId = "ORD_" + suffix;
        BigDecimal unitPrice = new BigDecimal("10.00");
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity));
        String payload = "{\"orderId\":\"" + orderId + "\",\"orderSequence\":1,\"eventType\":\"ORDER_PLACED\"}";
        long[] keys = jdbc.queryForObject("""
                with r as (
                    insert into roles(role_id, role_code, role_name)
                    values (?, ?, 'Fixture') returning id),
                u as (
                    insert into users(user_id, user_email, user_password_hash, role_id)
                    select ?, ?, 'unused', r.id from r returning id),
                c as (
                    insert into customers(customer_id, user_id, customer_name, customer_phone)
                    select ?, u.id, 'Fixture', '000' from u returning id),
                p as (
                    insert into products(product_id, product_name, product_description,
                                         product_status, product_slug)
                    values (?, 'Fixture', 'fixture', 'ACTIVE', ?) returning id),
                v as (
                    insert into variants(variant_id, product_id, variant_sku,
                                         variant_price, variant_stock)
                    select ?, p.id, ?, 10, ? from p returning id),
                o as (
                    insert into orders(order_id, customer_id, order_status, order_total,
                                       order_address, order_recipient_name, order_phone,
                                       event_sequence, payment_expires_at)
                    select ?, c.id, ?, ?, 'fixture', 'Fixture', '000', 1, ?::timestamptz
                      from c returning id, order_id),
                oi as (
                    insert into order_items(order_id, variant_id, quantity, unit_price)
                    select o.id, v.id, ?, ? from o, v returning order_id),
                pay as (
                    insert into payments(payment_id, order_id, payment_method,
                                         payment_status, payment_amount)
                    select ?, o.id, ?, ?, ? from o returning id),
                ev as (
                    insert into event_outbox(event_id, order_id, order_sequence, event_type,
                                             schema_version, payload, available_at)
                    select ?, o.id, 1, 'ORDER_PLACED', 1, ?::jsonb, clock_timestamp()
                      from o returning order_id)
                select o.id, v.id from o, v
                """, (rows, index) -> new long[]{rows.getLong(1), rows.getLong(2)},
                "R" + suffix, suffix,
                "U" + suffix, suffix + "@example.invalid",
                "C" + suffix,
                "P" + suffix, suffix,
                variantId, suffix, reservedStock,
                orderId, status.name(), total,
                deadline == null ? null : Timestamp.from(deadline),
                quantity, unitPrice,
                "PAY_" + suffix, method.name(), paymentStatus.name(), total,
                UUID.randomUUID(), payload);
        return new Fixture(keys[0], orderId, keys[1], total, method);
    }

    private static PaymentSettlementService.Result pay(Fixture f, boolean success, String reference) {
        return settlement.settle(f.orderId(), f.amount(), success, reference);
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static int pid() {
        return ((Number) entityManager.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
    }

    private static int backendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("select pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String awaitBlocked(Connection observer, int holder, Future<?> future) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            try (var refresh = observer.createStatement()) {
                refresh.execute("select pg_stat_clear_snapshot()");
            }
            try (PreparedStatement query = observer.prepareStatement(
                    "select query from pg_stat_activity where wait_event_type='Lock' "
                            + "and ?=any(pg_blocking_pids(pid))")) {
                query.setInt(1, holder);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) return rows.getString(1);
                }
            }
            assertThat(future.isDone()).as("waiter must overlap the held transaction").isFalse();
        }
        throw new AssertionError("No observable PostgreSQL lock overlap");
    }

    private static Race runTogether(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome> one = executor.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                first.run();
                return new Outcome(null, null);
            });
            Future<Outcome> two = executor.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                second.run();
                return new Outcome(null, null);
            });
            start.countDown();
            return new Race(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        }
    }

    private static Outcome attempt(Supplier<Object> action) {
        try {
            return new Outcome(action.get(), null);
        } catch (RuntimeException error) {
            return new Outcome(null, error);
        }
    }

    private static void lockRow(Connection connection, String table, long id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from " + table + " where id=? for update")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    private static void setDeadline(Fixture f, Instant deadline) {
        jdbc.update("update orders set payment_expires_at=? where id=?",
                Timestamp.from(deadline), f.id());
    }

    private static void setDeadlinePast(Fixture f) {
        jdbc.update("update orders set payment_expires_at=clock_timestamp()-interval '1 second' where id=?",
                f.id());
    }

    private static void setDeadlineNow(Fixture f) {
        jdbc.update("update orders set payment_expires_at=clock_timestamp() where id=?", f.id());
    }

    private static Instant deadline(Fixture f) {
        return jdbc.queryForObject("select payment_expires_at from orders where id=?",
                Timestamp.class, f.id()).toInstant();
    }

    private static Instant releaseMarker(long orderPk) {
        Timestamp marker = jdbc.queryForObject(
                "select stock_released_at from orders where id=?", Timestamp.class, orderPk);
        return marker == null ? null : marker.toInstant();
    }

    private static Instant paidAt(long orderPk) {
        Timestamp value = jdbc.queryForObject(
                "select paid_at from payments where order_id=?", Timestamp.class, orderPk);
        return value == null ? null : value.toInstant();
    }

    private static String transactionId(long orderPk) {
        return jdbc.queryForObject(
                "select payment_transaction_id from payments where order_id=?", String.class, orderPk);
    }

    private static String cancellationReason(long orderPk) {
        return jdbc.queryForObject(
                "select cancellation_reason from orders where id=?", String.class, orderPk);
    }

    private static long eventSequence(long orderPk) {
        return jdbc.queryForObject(
                "select event_sequence from orders where id=?", Long.class, orderPk);
    }

    private static OrderStatus orderStatus(long orderPk) {
        return OrderStatus.valueOf(jdbc.queryForObject(
                "select order_status from orders where id=?", String.class, orderPk));
    }

    private static PaymentStatus paymentStatus(long orderPk) {
        return PaymentStatus.valueOf(jdbc.queryForObject(
                "select payment_status from payments where order_id=?", String.class, orderPk));
    }

    private static long paymentPk(long orderPk) {
        return jdbc.queryForObject("select id from payments where order_id=?", Long.class, orderPk);
    }

    private static int stock(Fixture f) {
        return jdbc.queryForObject(
                "select variant_stock from variants where id=?", Integer.class, f.variant());
    }

    private static long cancelledCount() {
        return jdbc.queryForObject("""
                select count(*) from orders
                 where order_status='CANCELLED' and cancellation_reason='PAYMENT_EXPIRED'
                """, Long.class);
    }

    private static Map<String, Object> snapshot(Fixture f) {
        return Map.of("order", jdbc.queryForMap("select * from orders where id=?", f.id()),
                "payments", jdbc.queryForList("select * from payments where order_id=?", f.id()),
                "items", jdbc.queryForList("select * from order_items where order_id=? order by id", f.id()),
                "stock", jdbc.queryForList("select id,variant_stock,is_deleted from variants order by id"),
                "events", jdbc.queryForList("select * from event_outbox where order_id=? order by order_sequence", f.id()));
    }

    private static double claims() {
        var counter = registry.find(OrderExpiryWorker.CLAIMS_METRIC).counter();
        return counter == null ? 0 : counter.count();
    }

    private static double failures() {
        var counter = registry.find(OrderExpiryWorker.FAILURE_METRIC).counter();
        return counter == null ? 0 : counter.count();
    }

    private record Fixture(long id, String orderId, long variant,
                           BigDecimal amount, PaymentMethod method) { }

    private record Outcome(Object value, RuntimeException error) { }

    private record Race(Outcome first, Outcome second) { }

    /** Freezes only when explicitly set; ordinary calls use real clock_timestamp(). */
    static class ControlledClock extends DatabaseClock {
        final ThreadLocal<Instant> fixed = new ThreadLocal<>();
        final AtomicInteger samples = new AtomicInteger();

        ControlledClock(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public Instant now() {
            samples.incrementAndGet();
            return fixed.get() == null ? super.now() : fixed.get();
        }
    }

    @Configuration
    @Import({CheckoutInventoryPostgresTest.Config.class, PaymentSettlementService.class})
    static class Config {
        @Bean
        @Primary
        ControlledClock controlledClock(JdbcTemplate jdbc) {
            return new ControlledClock(jdbc);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        OrderExpiryWorker orderExpiryWorker(OrderLifecycleService lifecycle,
                                            ReservationPolicy policy,
                                            MeterRegistry registry) {
            return new OrderExpiryWorker(lifecycle, policy, registry);
        }
    }
}
