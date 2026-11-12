package com.project.dacn;

import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.dto.request.cart.CartItemAddRequest;
import com.project.dacn.dto.request.cart.CartItemUpdateRequest;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.repository.cart.CartRepository;
import com.project.dacn.service.cart.CartService;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real transactions, service proxies and repositories; no Docker, H2 or repository doubles. */
class CartWriteConcurrencyPostgresTest {
    enum Edit { ADD, SET, REMOVE, CLEAR, CHECKOUT, GET }

    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static EntityManager entityManager;
    private static TransactionTemplate transaction;
    private static CartService service;

    @BeforeAll
    static void startOnlyOwnedScratch() throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate"))
                .setLevel(ch.qos.logback.classic.Level.ERROR);
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CartService.class))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        database = ExternalPostgresDatabase.create();
        ownedName = nameOf(database);
        try {
            verifyIdentity(database);
            database.migrate();
            DataSource source = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
            jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(ownedName);
            // No Boot configuration, Flyway auto-configuration or SQL initializer can inherit an application URL.
            context = new AnnotationConfigApplicationContext();
            context.registerBean(DataSource.class, () -> source);
            context.register(Config.class);
            context.refresh();
            entityManager = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
            transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            service = context.getBean(CartService.class);
        } catch (Exception | AssertionError failure) {
            cleanup();
            throw failure;
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        try {
            if (context != null) {
                context.close();
                context = null;
            }
        } finally {
            if (database != null) {
                dropAndVerify(database);
                database = null;
            }
        }
    }

    @ParameterizedTest(name = "{0}, editFirst={1}")
    @CsvSource({"ADD,true", "ADD,false", "SET,true", "SET,false", "REMOVE,true", "REMOVE,false",
            "CLEAR,true", "CLEAR,false", "CHECKOUT,true", "CHECKOUT,false"})
    void everyWriterSerializesAgainstCheckout(Edit edit, boolean editFirst) throws Exception {
        Fixture fixture = seed(true);
        Edit first = editFirst ? edit : Edit.CHECKOUT;
        Edit second = editFirst ? Edit.CHECKOUT : edit;
        Race race = race(() -> invoke(first, fixture), () -> invoke(second, fixture));
        assertThat(race.first.error).isNull();
        int ordered = editFirst ? switch (edit) {
            case ADD, SET -> 3;
            case REMOVE, CLEAR -> 0;
            default -> 2;
        } : 2;
        if (editFirst && (edit == Edit.REMOVE || edit == Edit.CLEAR) || edit == Edit.CHECKOUT) {
            assertThat(race.second.error).isInstanceOf(IllegalArgumentException.class).hasMessage("cart is empty");
        } else if (!editFirst && (edit == Edit.SET || edit == Edit.REMOVE)) {
            assertThat(race.second.error).isInstanceOf(ResourceNotFoundException.class);
        } else {
            assertThat(race.second.error).isNull();
        }
        int remaining = !editFirst && edit == Edit.ADD ? 1 : 0;
        assertSnapshot(fixture, ordered, remaining);
        if (ordered > 0) {
            Object checkout = editFirst && edit != Edit.CHECKOUT ? race.second.value : race.first.value;
            assertThat(checkout).isInstanceOf(OrderResponse.class);
            assertThat(((OrderResponse) checkout).items()).singleElement()
                    .satisfies(line -> assertThat(line.quantity()).isEqualTo(ordered));
            assertThat(((OrderResponse) checkout).total()).isEqualByComparingTo(Integer.toString(ordered * 10));
        }
        assertThat(race.waitingQuery).as("loser must wait at cart lock before reading items").contains("carts");
        System.out.printf("Cart race %s editFirst=%s: ordered=%d remaining=%d stock=%d; cart lock observed%n",
                edit, editFirst, ordered, remaining, 10 - ordered);
    }

    @ParameterizedTest
    @CsvSource({"GET,ADD,1", "ADD,GET,1", "GET,GET,0", "ADD,ADD,2"})
    void firstCreationSerializesThroughOnlyItsOwner(Edit first, Edit second, int quantity) throws Exception {
        Fixture fixture = seed(false);
        Race race = race(() -> invoke(first, fixture), () -> invoke(second, fixture));
        assertThat(race.first.error).isNull();
        assertThat(race.second.error).isNull();
        assertThat(race.waitingQuery).as("absent-cart loser waits for its customer row").contains("customers");
        assertThat(number("select count(*) from carts where customer_id = ?", fixture.customer)).isEqualTo(1);
        assertSnapshot(fixture, 0, quantity);
        assertThat(quantityOf(race.first.value)).isEqualTo(first == Edit.GET ? 0 : 1);
        assertThat(quantityOf(race.second.value)).isEqualTo(quantity);
        System.out.printf("Creation race %s/%s: one cart, quantity=%d; customer lock observed%n", first, second, quantity);
    }

    @ParameterizedTest
    @EnumSource(Edit.class)
    void eachExistingCartOperationTimesOutAndRollsBack(Edit edit) throws Exception {
        Fixture fixture = seed(true);
        assertBoundedConflict(fixture, "carts", edit);
        assertSnapshot(fixture, 0, 2);
        // Recovery is a fresh read and explicit edit, never an automatic checkout replay.
        assertThat(service.getDetail(fixture.customer).totalQuantity()).isEqualTo(2);
        assertThat(service.addItem(fixture.customer, new CartItemAddRequest(fixture.variant, 1)).quantity()).isEqualTo(3);
        assertSnapshot(fixture, 0, 3);
    }

    @ParameterizedTest
    @CsvSource({"GET", "ADD"})
    void firstCreationHasSameBoundAndNoPartialCart(Edit edit) throws Exception {
        Fixture fixture = seed(false);
        assertBoundedConflict(fixture, "customers", edit);
        assertThat(number("select count(*) from carts where customer_id = ?", fixture.customer)).isZero();
        assertSnapshot(fixture, 0, 0);
        assertThat(service.getDetail(fixture.customer).items()).isEmpty();
        assertThat(service.addItem(fixture.customer, new CartItemAddRequest(fixture.variant, 1)).quantity()).isEqualTo(1);
        assertThat(number("select count(*) from carts where customer_id = ?", fixture.customer)).isEqualTo(1);
    }

    @Test
    void positiveJpaHintDoesNotSetPostgresLockTimeout() throws Exception {
        Fixture fixture = seed(true);
        try (Connection holder = connect()) {
            holder.setAutoCommit(false);
            lock(holder, "carts", fixture.customer);
            long start = System.nanoTime();
            Outcome outcome = attempt(() -> {
                entityManager.createNativeQuery("set local lock_timeout = '3s'").executeUpdate();
                return entityManager.createQuery("select c from Cart c where c.customer.id = :id")
                        .setParameter("id", fixture.customer).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .setHint("jakarta.persistence.lock.timeout", 2000).getResultList();
            });
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(outcome.error).isInstanceOf(jakarta.persistence.PessimisticLockException.class);
            assertThat(elapsed).as("positive hint must not be mistaken for PostgreSQL SET LOCAL").isBetween(2800L, 6000L);
            holder.rollback();
            System.out.printf("Positive JPA hint 2000: server 3s timeout won, elapsed=%dms%n", elapsed);
        }
    }

    @Test
    void transactionLocalLockTimeoutResetsOnReusedPooledConnection() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setPoolName("task3-lock-scope-probe");
        try (HikariDataSource pool = new HikariDataSource(config)) {
            int sessionPid;
            try (Connection connection = pool.getConnection()) {
                sessionPid = backendPid(connection);
                try (var statement = connection.createStatement()) {
                    statement.execute("set lock_timeout = '2s'");
                }
            }
            try (Connection reused = pool.getConnection()) {
                assertThat(backendPid(reused)).isEqualTo(sessionPid);
                assertThat(lockTimeout(reused)).as("negative control: a session-level SET survives pool return").isEqualTo("2s");
                try (var statement = reused.createStatement()) {
                    statement.execute("set lock_timeout = '0'");
                }
            }

            int transactionPid;
            try (Connection connection = pool.getConnection()) {
                transactionPid = backendPid(connection);
                assertThat(transactionPid).isEqualTo(sessionPid);
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute("set local lock_timeout = '2s'");
                }
                assertThat(lockTimeout(connection)).isEqualTo("2s");
                connection.rollback();
            }
            try (Connection reused = pool.getConnection()) {
                assertThat(backendPid(reused)).as("single-connection pool reused the same backend").isEqualTo(transactionPid);
                assertThat(lockTimeout(reused)).as("SET LOCAL must not survive the transaction on a reused pooled connection").isEqualTo("0");
            }
            System.out.printf("Pooled lock scope: pid=%d reused; session control=2s; SET LOCAL reset=0%n", transactionPid);
        }
    }

    @Test
    void anotherCustomersCartDoesNotWaitForHeldOwner() throws Exception {
        Fixture held = seed(false);
        Fixture independent = seed(false);
        try (Connection holder = connect()) {
            holder.setAutoCommit(false);
            lock(holder, "customers", held.customer);
            assertThat(service.addItem(independent.customer, new CartItemAddRequest(independent.variant, 1)).quantity())
                    .isEqualTo(1);
            holder.rollback();
        }
        assertThat(number("select count(*) from carts where customer_id = ?", held.customer)).isZero();
    }

    @Test
    void uniqueCartConstraintStillRejectsBypassAndFailedSetupDropsOnlyOwnedScratch() throws Exception {
        Fixture fixture = seed(true);
        assertThatThrownBy(() -> jdbc.update("insert into carts(cart_id,customer_id) values (?,?)",
                "CRT_duplicate_" + UUID.randomUUID(), fixture.customer))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("carts_customer_id_key");
        ExternalPostgresDatabase failed = ExternalPostgresDatabase.create();
        try {
            assertThat(nameOf(failed)).isNotEqualTo(ownedName);
            verifyIdentity(failed);
            assertThatThrownBy(() -> {
                try (Connection connection = DriverManager.getConnection(failed.jdbcUrl(), failed.username(), failed.password())) {
                    connection.createStatement().execute("insert into task3_missing_fixture values (1)");
                }
            }).isInstanceOf(java.sql.SQLException.class).extracting("SQLState").isEqualTo("42P01");
        } finally {
            dropAndVerify(failed);
        }
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(ownedName);
    }

    private static void assertBoundedConflict(Fixture fixture, String table, Edit edit) throws Exception {
        try (Connection holder = connect(); Connection observer = connect(); var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            lock(holder, table, fixture.customer);
            int holderPid = backendPid(holder);
            AtomicInteger waiterPid = new AtomicInteger();
            long start = System.nanoTime();
            Future<Outcome> future = pool.submit(() -> attempt(() -> {
                waiterPid.set(pid());
                entityManager.createNativeQuery("set local lock_timeout = '7s'").executeUpdate();
                return invoke(edit, fixture);
            }));
            assertThat(awaitBlocked(observer, holderPid, waiterPid, future)).contains("for no key update");
            long blockedAt = System.nanoTime();
            Outcome outcome = future.get(10, TimeUnit.SECONDS);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            long waitMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - blockedAt);
            assertThat(outcome.error).as("contention is a bounded recoverable conflict").isInstanceOf(ConcurrencyFailureException.class);
            assertThat(waitMillis).as("server-observed wait after the lock block, below the 7s probe setting").isBetween(1200L, 5000L);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController(outcome.error))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get("/task3-conflict"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("code").value(409))
                    .andExpect(jsonPath("message").value("resource is busy; refresh state before trying again"))
                    .andExpect(jsonPath("data").doesNotExist())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))));
            holder.rollback();
            transaction.executeWithoutResult(status -> assertThat(entityManager.createNativeQuery("show lock_timeout")
                    .getSingleResult().toString()).isEqualTo("0"));
            System.out.printf("Bounded %s/%s: 409, wait=%dms, total=%dms, rollback and transaction-local reset verified%n",
                    table, edit, waitMillis, elapsed);
        }
    }

    private static Race race(Supplier<Object> first, Supplier<Object> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2); Connection observer = connect()) {
            Future<Outcome> winner = pool.submit(() -> attempt(() -> {
                firstPid.set(pid());
                Object value = first.get();
                entityManager.flush();
                ready.countDown();
                await(release);
                return value;
            }));
            Future<Outcome> loser = null;
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).as("winner service completed inside its uncommitted transaction").isTrue();
                loser = pool.submit(() -> attempt(() -> {
                    secondPid.set(pid());
                    return second.get();
                }));
                String waiting = awaitBlocked(observer, firstPid.get(), secondPid, loser);
                release.countDown();
                return new Race(winner.get(10, TimeUnit.SECONDS), loser.get(10, TimeUnit.SECONDS), waiting);
            } finally {
                release.countDown();
                winner.get(15, TimeUnit.SECONDS);
                if (loser != null) loser.get(15, TimeUnit.SECONDS);
            }
        }
    }

    private static String awaitBlocked(Connection observer, int holderPid, AtomicInteger waiterPid, Future<?> waiter) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (PreparedStatement statement = observer.prepareStatement("select query from pg_stat_activity where pid = ? "
                    + "and wait_event_type = 'Lock' and ? = any(pg_blocking_pids(pid))")) {
                statement.setInt(1, waiterPid.get());
                statement.setInt(2, holderPid);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) return rows.getString(1);
                }
            }
            assertThat(waiter.isDone()).as("loser cannot finish before winning transaction commits").isFalse();
        }
        throw new AssertionError("PostgreSQL did not observe loser waiting on winning transaction");
    }

    private static Outcome attempt(Supplier<Object> action) {
        try {
            return new Outcome(transaction.execute(status -> action.get()), null);
        } catch (RuntimeException failure) {
            return new Outcome(null, failure);
        }
    }

    private static Object invoke(Edit edit, Fixture fixture) {
        return switch (edit) {
            case ADD -> service.addItem(fixture.customer, new CartItemAddRequest(fixture.variant, 1));
            case SET -> service.setQuantity(fixture.customer, fixture.item, new CartItemUpdateRequest(3));
            case REMOVE -> { service.removeItem(fixture.customer, fixture.item); yield null; }
            case CLEAR -> { service.clear(fixture.customer); yield null; }
            case GET -> service.getDetail(fixture.customer);
            case CHECKOUT -> service.checkout(fixture.customer,
                    new CartCheckoutRequest("fixture", "Fixture", "000", PaymentMethod.COD));
        };
    }

    private static void assertSnapshot(Fixture fixture, int ordered, int remaining) {
        assertThat(number("select count(*) from orders where customer_id = ?", fixture.customer)).isEqualTo(ordered == 0 ? 0 : 1);
        assertThat(number("select count(*) from payments p join orders o on o.id=p.order_id where o.customer_id=?", fixture.customer))
                .isEqualTo(ordered == 0 ? 0 : 1);
        assertThat(number("select coalesce(sum(i.quantity),0) from order_items i join orders o on o.id=i.order_id where o.customer_id=?", fixture.customer))
                .isEqualTo(ordered);
        assertThat(number("select coalesce(sum(i.quantity),0) from cart_items i join carts c on c.id=i.cart_id where c.customer_id=?", fixture.customer))
                .isEqualTo(remaining);
        assertThat(number("select variant_stock from variants where variant_id=?", fixture.variant)).isEqualTo(10 - ordered);
        assertThat(number("select count(*) from cart_items i join carts c on c.id=i.cart_id where c.customer_id=?", fixture.customer))
                .isEqualTo(remaining == 0 ? 0 : 1);
    }

    private static Fixture seed(boolean cart) {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(ownedName);
        String suffix = UUID.randomUUID().toString();
        long role = jdbc.queryForObject("insert into roles(role_id,role_code,role_name) values (?,?,'Fixture') returning id", Long.class, "ROL_" + suffix, suffix);
        long user = jdbc.queryForObject("insert into users(user_id,user_email,user_password_hash,role_id) values (?,?,'unused',?) returning id", Long.class,
                "USR_" + suffix, suffix + "@example.invalid", role);
        long customer = jdbc.queryForObject("insert into customers(customer_id,user_id,customer_name,customer_phone) values (?,?,'Fixture','000') returning id", Long.class,
                "CUS_" + suffix, user);
        long product = jdbc.queryForObject("insert into products(product_id,product_name,product_description,product_status,product_slug) values (?,'Fixture','fixture','ACTIVE',?) returning id", Long.class, "PRD_" + suffix, "fixture-" + suffix);
        String variant = "VAR_" + suffix;
        long variantKey = jdbc.queryForObject("insert into variants(variant_id,product_id,variant_sku,variant_price,variant_stock) values (?,?,?,10,10) returning id", Long.class,
                variant, product, suffix);
        String item = "ITM_" + suffix;
        if (cart) {
            long cartKey = jdbc.queryForObject("insert into carts(cart_id,customer_id) values (?,?) returning id", Long.class, "CRT_" + suffix, customer);
            jdbc.update("insert into cart_items(cart_item_id,cart_id,variant_id,quantity) values (?,?,?,2)", item, cartKey, variantKey);
        }
        return new Fixture(customer, variant, item);
    }

    private static int quantityOf(Object value) {
        return value instanceof CartDetailResponse detail ? detail.totalQuantity() : ((CartItemResponse) value).quantity();
    }

    private static long number(String sql, Object parameter) {
        return jdbc.queryForObject(sql, Long.class, parameter);
    }

    private static int pid() {
        return ((Number) entityManager.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Transaction latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static int backendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("select pg_backend_pid()")) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static String lockTimeout(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("show lock_timeout")) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void lock(Connection connection, String table, long customer) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("select id from " + table
                + " where " + (table.equals("carts") ? "customer_id" : "id") + " = ? for update")) {
            statement.setLong(1, customer);
            try (ResultSet rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    private static String nameOf(ExternalPostgresDatabase owned) {
        return URI.create(owned.jdbcUrl().substring("jdbc:".length())).getPath().substring(1);
    }

    private static void verifyIdentity(ExternalPostgresDatabase owned) throws Exception {
        assertThat(nameOf(owned)).matches("verify_event_foundation_[0-9a-f]{12}");
        try (Connection connection = DriverManager.getConnection(owned.jdbcUrl(), owned.username(), owned.password());
             var statement = connection.createStatement(); var rows = statement.executeQuery("select current_database()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(nameOf(owned));
        }
        System.out.println("Verified Task3 scratch identity: " + nameOf(owned));
    }

    private static void dropAndVerify(ExternalPostgresDatabase owned) throws Exception {
        owned.drop();
        try (Connection connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv("TEST_POSTGRES_USERNAME"), System.getenv("TEST_POSTGRES_PASSWORD"));
             var statement = connection.prepareStatement("select 1 from pg_database where datname=?")) {
            statement.setString(1, nameOf(owned));
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isFalse(); }
        }
        System.out.println("Confirmed Task3 scratch cleanup: " + nameOf(owned));
    }

    private record Fixture(long customer, String variant, String item) { }
    private record Outcome(Object value, RuntimeException error) { }
    private record Race(Outcome first, Outcome second, String waitingQuery) { }

    @RestController
    static class FailureController {
        private final RuntimeException failure;
        FailureController(RuntimeException failure) { this.failure = failure; }
        @GetMapping("/task3-conflict")
        Object fail() { throw failure; }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = "com.project.dacn.repository")
    @Import({CartService.class, CartCheckoutTest.CheckoutMapperTestConfig.class})
    static class Config {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.project.dacn.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.show_sql", "false"));
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
