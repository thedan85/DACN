package com.project.dacn;

import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.dto.request.catalog.VariantUpdateRequest;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.cart.CartService;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.VariantService;
import com.project.dacn.service.order.OrderLifecycleService;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real application transitions and row-lock overlap on an invocation-owned remote PG scratch. */
class VariantDeleteConcurrencyPostgresTest {
    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static HikariDataSource pool;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static EntityManager em;
    private static TransactionTemplate transaction;
    private static VariantService variants;
    private static VariantRepository repo;

    @BeforeAll
    static void start() throws Exception {
        database = ExternalPostgresDatabase.create();
        ownedName = URI.create(database.jdbcUrl().substring(5)).getPath().substring(1);
        try {
            assertThat(ownedName).matches("verify_event_foundation_[0-9a-f]{12}");
            try (Connection c = connect(); Statement s = c.createStatement();
                 ResultSet rows = s.executeQuery("select current_database()")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(ownedName);
            }
            System.out.println("Verified Task10 scratch identity: " + ownedName);
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
            context.register(CheckoutInventoryPostgresTest.Config.class);
            context.refresh();
            jdbc = context.getBean(JdbcTemplate.class);
            em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
            transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            repo = context.getBean(VariantRepository.class);
            variants = new VariantService(repo, context.getBean(ProductRepository.class),
                    org.mapstruct.factory.Mappers.getMapper(CatalogMapper.class), mock(CatalogCache.class));
            org.springframework.test.util.ReflectionTestUtils.setField(variants, "entityManager", em);
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
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (context != null) context.close();
        if (pool != null) pool.close();
        if (database != null) {
            database.drop();
            try (Connection c = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                    System.getenv("TEST_POSTGRES_USERNAME"), System.getenv("TEST_POSTGRES_PASSWORD"));
                 PreparedStatement s = c.prepareStatement("select 1 from pg_database where datname=?")) {
                s.setString(1, ownedName);
                try (ResultSet rows = s.executeQuery()) { assertThat(rows.next()).isFalse(); }
            }
            System.out.println("Confirmed Task10 scratch cleanup: " + ownedName);
        }
    }

    @Test
    void explicitStockIsRevisionCheckedBeforeMetadataAndReturnsRefreshedRevision() {
        Fixture f = fixture();
        String sku = jdbc.queryForObject("select variant_sku from variants where id=?", String.class, f.variant());
        assertThatThrownBy(() -> update(f, new VariantUpdateRequest("changed", BigDecimal.ONE, 10, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> update(f, new VariantUpdateRequest("changed", BigDecimal.ONE, 10, -1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> update(f, new VariantUpdateRequest("changed", BigDecimal.ONE, -1, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertStock(f, 10, 0, false);
        var noOp = update(f, new VariantUpdateRequest(null, null, 10, 0L));
        assertThat(noOp.stockRevision()).isZero();
        var changed = update(f, new VariantUpdateRequest(null, null, 7, 0L));
        assertThat(changed.stock()).isEqualTo(7);
        assertThat(changed.stockRevision()).isEqualTo(1L);
        // Equal numeric stock is still stale when its revision differs.
        var failure = catchThrowable(() -> update(f, new VariantUpdateRequest("changed", BigDecimal.ONE, 7, 0L)));
        assertThat(failure).isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
        var envelope = new GlobalExceptionHandler().handleConcurrency(
                (org.springframework.dao.ConcurrencyFailureException) failure);
        assertThat(envelope.getStatusCode().value()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select variant_sku from variants where id=?", String.class, f.variant()))
                .isEqualTo(sku);
        assertThat(jdbc.queryForObject("select variant_price from variants where id=?", BigDecimal.class, f.variant()))
                .isEqualByComparingTo("10");
        assertStock(f, 7, 1, false);
        transaction.executeWithoutResult(s -> variants.replace(f.variantId(),
                new VariantUpdateRequest("metadata", new BigDecimal("12"), null, null)));
        assertStock(f, 7, 1, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void checkoutAndDeleteForceBothLockWinners(boolean checkoutFirst) throws Exception {
        Fixture f = fixture();
        Supplier<Object> checkout = () -> checkout(f);
        Supplier<Object> delete = () -> { variants.delete(f.variantId()); return null; };
        Race race = overlap(f, checkoutFirst ? checkout : delete, checkoutFirst ? delete : checkout, 10, 0);
        assertThat(race.winner().error()).isNull();
        if (checkoutFirst) {
            assertThat(race.loser().error()).isNull();
            assertStock(f, 7, 1, true);
            assertThat(number("select count(*) from orders")).isEqualTo(1);
            assertFacts("ORDER_PLACED");
        } else {
            assertThat(race.loser().error()).isInstanceOf(ResourceNotFoundException.class);
            assertStock(f, 10, 0, true);
            assertThat(number("select count(*) from orders")).isZero();
            assertFacts();
        }
        assertHidden(f);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectionReleaseAndDeleteForceBothLockWinnersAndReplayIsUnchanged(boolean releaseFirst) throws Exception {
        Fixture f = fixture();
        String orderId = checkout(f).orderId();
        assertStock(f, 7, 1, false);
        assertFacts("ORDER_PLACED");
        Supplier<Object> release = () -> context.getBean(OrderLifecycleService.class).reject(orderId);
        Supplier<Object> delete = () -> {
            variants.delete(f.variantId());
            if (!releaseFirst) assertStock(f, 7, 1, true);
            return null;
        };
        Race race = overlap(f, releaseFirst ? release : delete, releaseFirst ? delete : release, 7, 1);
        assertThat(race.winner().error()).isNull();
        assertThat(race.loser().error()).isNull();
        assertStock(f, 10, 2, true);
        assertThat(number("select count(*) from orders where stock_released_at is not null")).isEqualTo(1);
        assertFacts("ORDER_PLACED", "ORDER_REJECTED");
        Map<String, Object> before = snapshot(f);
        assertThatThrownBy(() -> context.getBean(OrderLifecycleService.class).reject(orderId))
                .isInstanceOf(com.project.dacn.common.exception.InvalidOrderStateException.class);
        assertThat(snapshot(f)).isEqualTo(before);
        assertHidden(f);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void metadataAndCheckoutPreserveFreshStockWithStaleManagedReader(boolean checkoutFirst) throws Exception {
        Fixture f = fixture();
        Supplier<Object> checkout = () -> checkout(f);
        Supplier<Object> metadata = () -> variants.update(f.variantId(),
                new VariantUpdateRequest("metadata", new BigDecimal("12"), null, null));
        Race race = overlap(f, checkoutFirst ? checkout : metadata, checkoutFirst ? metadata : checkout, 10, 0);
        assertThat(race.winner().error()).isNull();
        assertThat(race.loser().error()).isNull();
        assertStock(f, 7, 1, false);
        assertThat(jdbc.queryForObject("select variant_sku from variants where id=?", String.class, f.variant()))
                .isEqualTo("metadata");
        assertFacts("ORDER_PLACED");
    }

    @Test
    void checkoutWinnerRejectsStaleExplicitWriteWithoutPartialMetadata() throws Exception {
        Fixture f = fixture();
        String oldSku = jdbc.queryForObject("select variant_sku from variants where id=?", String.class, f.variant());
        Race race = overlap(f, () -> checkout(f), () -> variants.update(f.variantId(),
                new VariantUpdateRequest("wrong", BigDecimal.ONE, 7, 0L)), 10, 0);
        assertThat(race.winner().error()).isNull();
        assertThat(race.loser().error()).isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
        assertStock(f, 7, 1, false);
        assertThat(jdbc.queryForObject("select variant_sku from variants where id=?", String.class, f.variant()))
                .isEqualTo(oldSku);
        assertThat(jdbc.queryForObject("select variant_price from variants where id=?", BigDecimal.class, f.variant()))
                .isEqualByComparingTo("10");
        assertFacts("ORDER_PLACED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void heldVariantHasBoundedConflictAndZeroWrites(boolean delete) throws Exception {
        Fixture f = fixture();
        Map<String, Object> before = snapshot(f);
        AtomicInteger waiterPid = new AtomicInteger();
        try (Connection holder = connect(); Connection observer = connect();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (PreparedStatement s = holder.prepareStatement("select id from variants where id=? for update")) {
                s.setLong(1, f.variant()); s.executeQuery().close();
            }
            int holderPid = backendPid(holder);
            long started = System.nanoTime();
            Future<Outcome> future = executor.submit(() -> attempt(() -> transaction.execute(s -> {
                waiterPid.set(pid());
                if (delete) { variants.delete(f.variantId()); return null; }
                return variants.update(f.variantId(), new VariantUpdateRequest("blocked", BigDecimal.ONE, null, null));
            })));
            assertThat(awaitBlocked(observer, holderPid, waiterPid, future)).containsIgnoringCase("variants");
            Outcome result = future.get(10, TimeUnit.SECONDS);
            assertThat(result.error()).isInstanceOfAny(org.springframework.dao.ConcurrencyFailureException.class,
                    jakarta.persistence.PessimisticLockException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(1800L, 9000L);
            holder.rollback();
        }
        assertThat(snapshot(f)).isEqualTo(before);
        // SET LOCAL does not leak to the next transaction.
        transaction.executeWithoutResult(s ->
                assertThat(em.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo("0"));
        update(f, new VariantUpdateRequest("recovered", null, null, null));
        assertStock(f, 10, 0, false);
    }

    @Test
    void manualSqlAndNativeHistoricalReleaseIncrementOnlyChangedStock() {
        Fixture f = fixture();
        assertStock(f, 10, 0, false);
        jdbc.update("update variants set variant_stock=variant_stock where id=?", f.variant());
        jdbc.update("update variants set variant_price=12, is_deleted=true where id=?", f.variant());
        assertStock(f, 10, 0, true);
        jdbc.update("update variants set variant_stock=7 where id=?", f.variant());
        assertStock(f, 7, 1, true);
        transaction.executeWithoutResult(s -> assertThat(repo.incrementStockNative(f.variant(), 3)).isEqualTo(1));
        assertStock(f, 10, 2, true);
        assertHidden(f);
    }

    private static com.project.dacn.dto.response.catalog.VariantResponse update(Fixture f, VariantUpdateRequest request) {
        return transaction.execute(s -> variants.update(f.variantId(), request));
    }

    private static com.project.dacn.dto.response.order.OrderResponse checkout(Fixture f) {
        return context.getBean(CartService.class).checkout(f.customer(),
                new CartCheckoutRequest("fixture", "Fixture", "000", PaymentMethod.COD));
    }

    private static Map<String, Object> snapshot(Fixture f) {
        return Map.of("variant", jdbc.queryForMap("select * from variants where id=?", f.variant()),
                "orders", jdbc.queryForList("select * from orders order by id"),
                "payments", jdbc.queryForList("select * from payments order by id"),
                "facts", jdbc.queryForList("select * from event_outbox order by order_sequence"));
    }

    private static void assertFacts(String... types) {
        assertThat(jdbc.queryForList("select event_type from event_outbox order by order_sequence", String.class))
                .containsExactly(types);
        List<Long> sequences = jdbc.queryForList("select order_sequence from event_outbox order by order_sequence", Long.class);
        for (int i = 0; i < types.length; i++) assertThat(sequences.get(i)).isEqualTo(i + 1L);
        if (types.length > 0) assertThat(number("select event_sequence from orders")).isEqualTo(types.length);
    }

    private static long number(String sql) { return jdbc.queryForObject(sql, Long.class); }

    private static Race overlap(Fixture f, Supplier<Object> winner, Supplier<Object> loser,
                                int staleStock, long staleRevision) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger winnerPid = new AtomicInteger();
        AtomicInteger loserPid = new AtomicInteger();
        try (Connection observer = connect(); ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome> first = executor.submit(() -> attempt(() -> transaction.execute(s -> {
                winnerPid.set(pid());
                Object result = winner.get();
                em.flush();
                locked.countDown();
                await(proceed);
                return result;
            })));
            try {
                assertThat(locked.await(15, TimeUnit.SECONDS)).as("winner holds its actual mutation uncommitted").isTrue();
                Future<Outcome> second = executor.submit(() -> attempt(() -> transaction.execute(s -> {
                    loserPid.set(pid());
                    Variant stale = repo.findByVariantId(f.variantId()).orElseThrow();
                    assertThat(stale.getStock()).isEqualTo(staleStock);
                    assertThat(stale.getStockRevision()).isEqualTo(staleRevision);
                    assertThat(stale.getDeleted()).isFalse();
                    return loser.get();
                })));
                try {
                    String query = awaitBlocked(observer, winnerPid.get(), loserPid, second);
                    assertThat(query).containsIgnoringCase("variants");
                    System.out.printf("Observed Task10 variant lock winner=%d waiter=%d stock=%d revision=%d%n",
                            winnerPid.get(), loserPid.get(), staleStock, staleRevision);
                } finally { proceed.countDown(); }
                return new Race(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            } finally { proceed.countDown(); }
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static String awaitBlocked(Connection observer, int holder, AtomicInteger waiter, Future<?> future) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            try (Statement refresh = observer.createStatement()) { refresh.execute("select pg_stat_clear_snapshot()"); }
            try (PreparedStatement s = observer.prepareStatement("select query from pg_stat_activity where pid=? "
                    + "and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid))")) {
                s.setInt(1, waiter.get()); s.setInt(2, holder);
                try (ResultSet rows = s.executeQuery()) { if (rows.next()) return rows.getString(1); }
            }
            assertThat(future.isDone()).as("waiter must overlap the winning transaction").isFalse();
        }
        throw new AssertionError("No observable PostgreSQL variant lock overlap");
    }

    private static int pid() { return ((Number) em.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue(); }
    private static int backendPid(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery("select pg_backend_pid()")) {
            assertThat(rows.next()).isTrue(); return rows.getInt(1);
        }
    }
    private static Outcome attempt(Supplier<Object> action) {
        try { return new Outcome(action.get(), null); }
        catch (RuntimeException error) { return new Outcome(null, error); }
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        long product = jdbc.queryForObject("""
                insert into products(product_id,product_name,product_description,product_status,product_slug)
                values (?,'Fixture','fixture','ACTIVE',?) returning id
                """, Long.class, "PRD_" + suffix, "fixture-" + suffix);
        String variantId = "VAR_" + suffix;
        long variant = jdbc.queryForObject("""
                insert into variants(variant_id,product_id,variant_sku,variant_price,variant_stock)
                values (?,?,?,10,10) returning id
                """, Long.class, variantId, product, suffix);
        long role = jdbc.queryForObject("insert into roles(role_id,role_code,role_name) values (?,?,'Fixture') returning id",
                Long.class, "ROL_" + suffix, suffix);
        long user = jdbc.queryForObject("""
                insert into users(user_id,user_email,user_password_hash,role_id) values (?,?,'unused',?) returning id
                """, Long.class, "USR_" + suffix, suffix + "@example.invalid", role);
        long customer = jdbc.queryForObject("""
                insert into customers(customer_id,user_id,customer_name,customer_phone) values (?,?,'Fixture','000') returning id
                """, Long.class, "CUS_" + suffix, user);
        long cart = jdbc.queryForObject("insert into carts(cart_id,customer_id) values (?,?) returning id",
                Long.class, "CRT_" + suffix, customer);
        jdbc.update("insert into cart_items(cart_item_id,cart_id,variant_id,quantity) values (?,?,?,3)",
                "ITM_" + suffix, cart, variant);
        return new Fixture(variant, variantId, customer, "fixture-" + suffix);
    }

    private static void assertStock(Fixture f, int stock, long revision, boolean deleted) {
        Map<String, Object> row = jdbc.queryForMap(
                "select variant_stock,stock_revision,is_deleted from variants where id=?", f.variant());
        assertThat(row.get("variant_stock")).isEqualTo(stock);
        assertThat(row.get("stock_revision")).isEqualTo(revision);
        assertThat(row.get("is_deleted")).isEqualTo(deleted);
    }

    private static void assertHidden(Fixture f) {
        transaction.executeWithoutResult(s -> {
            assertThat(repo.findByVariantId(f.variantId())).isEmpty();
            assertThat(repo.findByProduct_SlugIgnoreCase(f.slug(), org.springframework.data.domain.Pageable.unpaged())).isEmpty();
            assertThatThrownBy(() -> variants.get(f.variantId(), true)).isInstanceOf(ResourceNotFoundException.class);
        });
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private record Fixture(long variant, String variantId, long customer, String slug) { }
    private record Outcome(Object value, RuntimeException error) { }
    private record Race(Outcome winner, Outcome loser) { }
}
