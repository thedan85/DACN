package com.project.dacn;

import com.project.dacn.service.order.OrderExpiryWorker;
import com.project.dacn.service.order.ReservationPolicy;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One bounded client-to-remote Supabase scratch burst: 50 due online
 * orders drained by the scheduled worker with the unchanged Hikari 5/1 pool.
 * Reports eligible expiry lag percentiles, processed/failure counts and pool
 * connection-acquire timing. The p95 target stays at 30 seconds; this is not a
 * hosting benchmark and a remote/co-tenanted scratch run is labelled as such.
 */
class ExpiryBurstPostgresTest {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ExpiryBurstPostgresTest.class);

    static final int ORDERS = 50;

    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static HikariDataSource pool;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static OrderExpiryWorker worker;
    private static SimpleMeterRegistry registry;

    @BeforeAll
    static void startOwnedDatabase() throws Exception {
        database = ExternalPostgresDatabase.create();
        ownedName = URI.create(database.jdbcUrl().substring(5)).getPath().substring(1);
        try {
            assertThat(ownedName).matches("verify_event_foundation_[0-9a-f]{12}");
            database.migrate();
            registry = new SimpleMeterRegistry();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(database.jdbcUrl());
            config.setUsername(database.username());
            config.setPassword(database.password());
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            config.setMetricRegistry(registry);
            pool = new HikariDataSource(config);
            log.info("Expiry burst scratch identity: {} (maxPool={}, minIdle={})",
                    ownedName, pool.getMaximumPoolSize(), pool.getMinimumIdle());
            context = newContext(pool);
            jdbc = context.getBean(JdbcTemplate.class);
            worker = context.getBean(OrderExpiryWorker.class);
            assertThat(jdbc.queryForObject("""
                    select count(*) from flyway_schema_history
                     where version = '34' and success
                    """, Long.class)).isEqualTo(1);
        } catch (Exception | AssertionError failure) {
            cleanup();
            throw failure;
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (context != null) context.close();
        if (pool != null) pool.close();
        if (database != null) {
            database.drop();
            try (Connection connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                    System.getenv("TEST_POSTGRES_USERNAME"), System.getenv("TEST_POSTGRES_PASSWORD"));
                 PreparedStatement statement = connection.prepareStatement(
                         "select 1 from pg_database where datname=?")) {
                statement.setString(1, ownedName);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isFalse();
                }
            }
            log.info("Confirmed expiry burst scratch cleanup: {}", ownedName);
            database = null;
        }
    }

    @Test
    void boundedExpiryBurstDrainsOneTickWithinThePoolBound() throws InterruptedException {
        for (int i = 0; i < ORDERS; i++) {
            seedDueOrder();
        }
        assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
        assertThat(pool.getMinimumIdle()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(ownedName);
        // All 50 become eligible together after fixture setup, then wait one polling interval.
        jdbc.update("update orders set payment_expires_at=clock_timestamp()");
        Thread.sleep(5_000);
        var acquire = registry.find("hikaricp.connections.acquire").timer();
        long beforeCount = acquire == null ? 0 : acquire.count();
        double beforeTotal = acquire == null ? 0 : acquire.totalTime(TimeUnit.MILLISECONDS);

        long started = System.nanoTime();
        worker.sweep();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        long processed = canceledCount();
        long expiredFacts = jdbc.queryForObject(
                "select count(*) from event_outbox where event_type='ORDER_EXPIRED'",
                Long.class);
        List<Double> lagMs = new ArrayList<>(jdbc.queryForList("""
                select extract(epoch from (stock_released_at - payment_expires_at)) * 1000
                  from orders
                 where stock_released_at is not null
                """, Double.class));
        lagMs.sort(null);
        double p50 = percentile(lagMs, 0.50);
        double p95 = percentile(lagMs, 0.95);
        double p99 = percentile(lagMs, 0.99);
        double claims = counter(OrderExpiryWorker.CLAIMS_METRIC);
        double failures = counter(OrderExpiryWorker.FAILURE_METRIC);
        long acquireCount = acquire == null ? 0 : acquire.count() - beforeCount;
        double acquireTotalMs = acquire == null ? 0 : acquire.totalTime(TimeUnit.MILLISECONDS) - beforeTotal;
        double acquireMeanMs = acquireCount == 0 ? 0 : acquireTotalMs / acquireCount;
        double acquireMaxMs = acquire == null ? 0 : acquire.max(TimeUnit.MILLISECONDS);
        double poolTimeouts = counter("hikaricp.connections.timeout");

        log.info("REMOTE_SUPABASE_SCRATCH orders={} processed={} expiredFacts={} failures={} claims={} "
                        + "lagMs p50={} p95={} p99={} wallClockMs={} acquireCount={} acquireMeanMs={} "
                        + "acquireMaxIncludingSetupMs={} poolTimeouts={} pendingAfter={}",
                ORDERS, processed, expiredFacts, (long) failures, (long) claims,
                p50, p95, p99, elapsedMs, acquireCount, acquireMeanMs, acquireMaxMs,
                poolTimeouts, pool.getHikariPoolMXBean().getThreadsAwaitingConnection());

        assertThat(processed).isEqualTo(ORDERS);
        assertThat(expiredFacts).isEqualTo(ORDERS);
        assertThat(failures).isZero();
        assertThat(claims).isEqualTo(ORDERS);
        assertThat(lagMs).hasSize(ORDERS);
        assertThat(p95).isLessThanOrEqualTo(30_000.0);
    }

    private static double percentile(List<Double> sorted, double quantile) {
        assertThat(sorted).isNotEmpty();
        int index = (int) Math.ceil(sorted.size() * quantile) - 1;
        return sorted.get(Math.max(index, 0));
    }

    private double counter(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private static long canceledCount() {
        return jdbc.queryForObject("""
                select count(*) from orders
                 where order_status='CANCELLED' and cancellation_reason='PAYMENT_EXPIRED'
                """, Long.class);
    }

    private static void seedDueOrder() {
        String suffix = UUID.randomUUID().toString();
        String variantId = "VAR_" + suffix;
        String orderId = "ORD_" + suffix;
        BigDecimal unitPrice = new BigDecimal("10.00");
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(3));
        String payload = "{\"orderId\":\"" + orderId + "\",\"orderSequence\":1,\"eventType\":\"ORDER_PLACED\"}";
        jdbc.update("""
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
                    select ?, p.id, ?, 10, 7 from p returning id),
                o as (
                    insert into orders(order_id, customer_id, order_status, order_total,
                                       order_address, order_recipient_name, order_phone,
                                       event_sequence, payment_expires_at)
                    select ?, c.id, 'PENDING', ?, 'fixture', 'Fixture', '000', 1,
                           clock_timestamp()
                      from c returning id),
                oi as (
                    insert into order_items(order_id, variant_id, quantity, unit_price)
                    select o.id, v.id, 3, ? from o, v returning order_id),
                pay as (
                    insert into payments(payment_id, order_id, payment_method,
                                         payment_status, payment_amount)
                    select ?, o.id, 'E_WALLET', 'PENDING', ? from o returning id)
                insert into event_outbox(event_id, order_id, order_sequence, event_type,
                                         schema_version, payload, available_at)
                select ?, o.id, 1, 'ORDER_PLACED', 1, ?::jsonb, clock_timestamp()
                  from o
                """,
                "R" + suffix, suffix,
                "U" + suffix, suffix + "@example.invalid",
                "C" + suffix,
                "P" + suffix, suffix,
                variantId, suffix,
                orderId, total,
                unitPrice,
                "PAY_" + suffix, total,
                UUID.randomUUID(), payload);
    }

    private static AnnotationConfigApplicationContext newContext(DataSource source) {
        var fresh = new AnnotationConfigApplicationContext();
        fresh.registerBean(DataSource.class, () -> source);
        fresh.register(Config.class);
        fresh.refresh();
        return fresh;
    }

    @Configuration
    @Import({CheckoutInventoryPostgresTest.Config.class})
    static class Config {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return registry;
        }

        @Bean
        OrderExpiryWorker orderExpiryWorker(com.project.dacn.service.order.OrderLifecycleService lifecycle,
                                            ReservationPolicy policy,
                                            MeterRegistry meterRegistry) {
            return new OrderExpiryWorker(lifecycle, policy, meterRegistry);
        }
    }
}
