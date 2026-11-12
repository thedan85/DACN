package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.exception.OutOfStockException;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.mapper.cart.CartMapper;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.messaging.outbox.OrderEventOutbox;
import com.project.dacn.repository.cart.CartItemRepository;
import com.project.dacn.repository.cart.CartRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.cart.CartService;
import com.project.dacn.service.cart.CompatibilityService;
import com.project.dacn.service.order.OrderLifecycleService;
import com.project.dacn.service.order.OrderResponseAssembler;
import com.project.dacn.service.order.ReservationPolicy;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
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
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real checkout transactions, PostgreSQL repositories, locks and the transactional outbox. */
class CheckoutInventoryPostgresTest {

    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static HikariDataSource pool;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static EntityManager entityManager;
    private static TransactionTemplate transaction;
    private static CartService service;
    private static CartService pausedOnlineService;
    private static CartService inactivePolicyService;
    private static ObjectMapper mapper;

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
            HikariConfig hikari = new HikariConfig();
            hikari.setJdbcUrl(database.jdbcUrl());
            hikari.setUsername(database.username());
            hikari.setPassword(database.password());
            hikari.setMaximumPoolSize(5);
            hikari.setMinimumIdle(1);
            hikari.setPoolName("task4-checkout-pg");
            pool = new HikariDataSource(hikari);
            DataSource source = pool;
            context = new AnnotationConfigApplicationContext();
            context.registerBean(DataSource.class, () -> source);
            context.register(Config.class);
            context.refresh();
            jdbc = context.getBean(JdbcTemplate.class);
            mapper = context.getBean(ObjectMapper.class);
            assertThat(jdbc.queryForObject("""
                    select count(*) from flyway_schema_history
                     where version = '34' and success
                    """, Long.class)).isEqualTo(1);
            System.out.println("Flyway history: V34 successful");
            entityManager = SharedEntityManagerCreator.createSharedEntityManager(
                    context.getBean(EntityManagerFactory.class));
            transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            service = context.getBean(CartService.class);
            pausedOnlineService = serviceWith(new ReservationPolicy(true, true, false));
            inactivePolicyService = serviceWith(new ReservationPolicy(false, false, false));
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
    void lastItemHasOneWinnerAcrossDifferentCarts() throws Exception {
        long variant = variant("11.00", 1);
        String variantId = variantBusinessId(variant);
        long firstCustomer = customerWithCart(variant, 1);
        long secondCustomer = customerWithCart(variant, 1);

        Race race = race(
                () -> checkout(firstCustomer, PaymentMethod.COD),
                () -> checkout(secondCustomer, PaymentMethod.COD));

        assertThat(List.of(race.first, race.second))
                .as("outcomes: %s / %s", race.first.error, race.second.error)
                .filteredOn(Outcome::isSuccess).hasSize(1);
        assertThat(List.of(race.first, race.second))
                .as("loser: %s / %s", race.first.error, race.second.error)
                .filteredOn(outcome -> outcome.error instanceof OutOfStockException
                        || isBoundedConflict(outcome.error)).hasSize(1);
        assertThat(number("select count(*) from orders")).isEqualTo(1);
        assertThat(number("select count(*) from payments")).isEqualTo(1);
        assertThat(number("select coalesce(sum(quantity),0) from order_items")).isEqualTo(1);
        assertThat(number("select count(*) from event_outbox")).isEqualTo(1);
        assertThat(number("select variant_stock from variants where id=?", variant)).isZero();
        assertThat(cartQuantity(firstCustomer)).isIn(0L, 1L);
        assertThat(cartQuantity(secondCustomer)).isIn(0L, 1L);
        assertThat(cartQuantity(firstCustomer) + cartQuantity(secondCustomer)).isEqualTo(1);
        String orderId = jdbc.queryForObject("select order_id from orders", String.class);
        assertPlacementFact(orderId, PaymentMethod.COD, "11.00");
        System.out.printf("Last item: one winner, one 409, stock=0, one order/fact (%s)%n", variantId);
    }

    @Test
    void sameCartRepeatCreatesOneOrder() throws Exception {
        long variant = variant("10.00", 10);
        String variantId = variantBusinessId(variant);
        long customer = customerWithCart(variant, 2);

        Race race = race(
                () -> checkout(customer, PaymentMethod.COD),
                () -> checkout(customer, PaymentMethod.COD));

        assertThat(List.of(race.first, race.second))
                .as("outcomes: %s / %s", race.first.error, race.second.error)
                .filteredOn(Outcome::isSuccess).hasSize(1);
        assertThat(List.of(race.first, race.second))
                .as("loser: %s / %s", race.first.error, race.second.error)
                .filteredOn(outcome -> (outcome.error instanceof IllegalArgumentException
                        && "cart is empty".equals(outcome.error.getMessage()))
                        || isBoundedConflict(outcome.error)).hasSize(1);
        assertThat(number("select count(*) from orders where customer_id=?", customer)).isEqualTo(1);
        assertThat(number("select count(*) from event_outbox")).isEqualTo(1);
        assertThat(number("select variant_stock from variants where id=?", variant)).isEqualTo(8);
        assertThat(cartQuantity(customer)).isZero();
        String orderId = jdbc.queryForObject("select order_id from orders", String.class);
        assertPlacementFact(orderId, PaymentMethod.COD, "20.00");
        System.out.printf("Same cart repeat: one order/fact, stock=8 (%s)%n", variantId);
    }

    @Test
    void checkoutRefreshesPriceAndStockAfterLockWait() throws Exception {
        long variant = variant("10.00", 5);
        String variantId = variantBusinessId(variant);
        long customer = customerWithCart(variant, 1);
        Instant commitTime;
        Outcome outcome;
        String waitingQuery;
        try (Connection holder = connect(); Connection observer = connect();
             ExecutorService pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "select id from variants where id=? for update")) {
                statement.setLong(1, variant);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            try (PreparedStatement statement = holder.prepareStatement(
                    "update variants set variant_price=25, variant_stock=1 where id=?")) {
                statement.setLong(1, variant);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            int holderPid = backendPid(holder);
            AtomicInteger waiterPid = new AtomicInteger();
            Future<Outcome> future = pool.submit(() -> attempt(() -> transaction.execute(status -> {
                waiterPid.set(pid());
                return checkout(customer, PaymentMethod.E_WALLET);
            })));
            waitingQuery = awaitBlocked(observer, holderPid, waiterPid, future);
            commitTime = clock(holder);
            holder.commit();
            outcome = future.get(15, TimeUnit.SECONDS);
        }

        assertThat(waitingQuery).as("checkout must wait on the variant row lock").contains("variants");
        assertThat(outcome.error).isNull();
        OrderResponse response = (OrderResponse) outcome.value;
        assertThat(response.total()).isEqualByComparingTo("25.00");
        assertThat(response.items()).singleElement()
                .satisfies(line -> assertThat(line.unitPrice()).isEqualByComparingTo("25.00"));
        assertThat(response.paymentExpiresAt()).isNotNull();
        assertThat(response.paymentExpiresAt())
                .as("activated online deadline is sampled after the variant lock wait")
                .isAfterOrEqualTo(commitTime.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertThat(number("select variant_stock from variants where id=?", variant)).isZero();
        assertThat(number("select count(*) from order_items where unit_price=25.00")).isEqualTo(1);
        assertPlacementFact(response.orderId(), PaymentMethod.E_WALLET, "25.00");
        System.out.printf("Lock wait refresh: waited on %s, price=25, stock=0, post-lock deadline%n",
                variantId);
    }

    @Test
    void lockWaitRefreshRejectsQuantityAboveCommittedStock() throws Exception {
        long variant = variant("10.00", 5);
        String variantId = variantBusinessId(variant);
        long customer = customerWithCart(variant, 2);
        Outcome outcome;
        String waitingQuery;
        try (Connection holder = connect(); Connection observer = connect();
             ExecutorService pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "select id from variants where id=? for update")) {
                statement.setLong(1, variant);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            try (PreparedStatement statement = holder.prepareStatement(
                    "update variants set variant_stock=1 where id=?")) {
                statement.setLong(1, variant);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            int holderPid = backendPid(holder);
            AtomicInteger waiterPid = new AtomicInteger();
            Future<Outcome> future = pool.submit(() -> attempt(() -> transaction.execute(status -> {
                waiterPid.set(pid());
                return checkout(customer, PaymentMethod.COD);
            })));
            waitingQuery = awaitBlocked(observer, holderPid, waiterPid, future);
            holder.commit();
            outcome = future.get(15, TimeUnit.SECONDS);
        }

        assertThat(waitingQuery).as("checkout must wait on the variant row lock").contains("variants");
        assertThat(outcome.error).isInstanceOf(OutOfStockException.class)
                .hasMessage("requested quantity 2 exceeds stock 1");
        assertThat(number("select variant_stock from variants where id=?", variant))
                .as("holder's committed stock is not changed by the rejected checkout").isEqualTo(1);
        assertThat(cartQuantity(customer)).as("cart survives rejected checkout").isEqualTo(2);
        assertThat(number("select count(*) from orders")).isZero();
        assertThat(number("select count(*) from payments")).isZero();
        assertThat(number("select count(*) from order_items")).isZero();
        assertThat(number("select count(*) from event_outbox")).isZero();
        System.out.printf("Lock wait stock refresh: waited on %s, rejected quantity 2 above committed "
                + "stock 1 via post-lock validation%n", variantId);
    }

    @Test
    void oneInsufficientLineWritesNothing() throws Exception {
        long enough = variant("10.00", 10);
        long insufficient = variant("5.00", 1);
        long customer = customer();
        long cart = cart(customer);
        addItem(cart, enough, 2);
        addItem(cart, insufficient, 2);

        assertThatThrownBy(() -> checkout(customer, PaymentMethod.COD))
                .isInstanceOf(OutOfStockException.class)
                .hasMessageContaining("requested quantity 2 exceeds stock 1");

        assertThat(number("select variant_stock from variants where id=?", enough)).isEqualTo(10);
        assertThat(number("select variant_stock from variants where id=?", insufficient)).isEqualTo(1);
        assertThat(number("select count(*) from orders")).isZero();
        assertThat(number("select count(*) from payments")).isZero();
        assertThat(number("select count(*) from order_items")).isZero();
        assertThat(number("select count(*) from event_outbox")).isZero();
        assertThat(cartQuantity(customer)).isEqualTo(4);
    }

    @Test
    void secondDeductionFailureRollsBackFirstDeduction() throws Exception {
        long first = variant("10.00", 10);
        long second = variant("5.00", 10);
        long customer = customer();
        long cart = cart(customer);
        addItem(cart, first, 2);
        addItem(cart, second, 2);

        long attempts;
        jdbc.execute("create sequence task4_native_update_attempts");
        try {
            jdbc.execute("""
                    create function task4_skip_second_variant_update() returns trigger
                    language plpgsql as $$
                    begin
                      if nextval('task4_native_update_attempts') = 2 then
                        return null;
                      end if;
                      return new;
                    end
                    $$
                    """);
            jdbc.execute("""
                    create trigger task4_skip_second_variant_update
                    before update on variants
                    for each row execute function task4_skip_second_variant_update()
                    """);

            assertThatThrownBy(() -> checkout(customer, PaymentMethod.COD))
                    .isInstanceOf(OutOfStockException.class)
                    .hasMessageMatching("insufficient stock for variant (" + first + "|" + second + ")");
        } finally {
            attempts = jdbc.queryForObject(
                    "select last_value from task4_native_update_attempts", Long.class);
            jdbc.execute("drop trigger if exists task4_skip_second_variant_update on variants");
            jdbc.execute("drop function if exists task4_skip_second_variant_update()");
            jdbc.execute("drop sequence if exists task4_native_update_attempts");
        }

        assertThat(attempts).as("both native deductions were attempted").isEqualTo(2);
        assertThat(number("select variant_stock from variants where id=?", first))
                .as("first native deduction rolled back").isEqualTo(10);
        assertThat(number("select variant_stock from variants where id=?", second))
                .as("second native deduction returned zero rows").isEqualTo(10);
        assertThat(number("select count(*) from orders")).isZero();
        assertThat(number("select count(*) from payments")).isZero();
        assertThat(number("select count(*) from order_items")).isZero();
        assertThat(number("select count(*) from event_outbox")).isZero();
        assertThat(cartQuantity(customer)).isEqualTo(4);
        System.out.println("Post-deduction failure: first deduction rolled back, second returned zero");
    }

    @Test
    void placementIsInvisibleBeforeCommitAndRollsBackEveryWrite() throws Exception {
        long variant = variant("10.00", 10);
        String variantId = variantBusinessId(variant);
        long customer = customerWithCart(variant, 2);

        OrderResponse rolledBack = transaction.execute(status -> {
            OrderResponse response = checkout(customer, PaymentMethod.COD);
            try {
                assertThat(observedNumber("select count(*) from orders"))
                        .as("order invisible before commit").isZero();
                assertThat(observedNumber("select count(*) from payments"))
                        .as("payment invisible before commit").isZero();
                assertThat(observedNumber("select count(*) from order_items"))
                        .as("items invisible before commit").isZero();
                assertThat(observedNumber("select count(*) from event_outbox"))
                        .as("fact invisible before commit").isZero();
                assertThat(observedNumber("select variant_stock from variants where id=?", variant))
                        .as("deduction invisible before commit").isEqualTo(10);
                assertThat(observedNumber("""
                        select coalesce(sum(i.quantity), 0)
                          from cart_items i join carts c on c.id = i.cart_id
                         where c.customer_id = ?
                        """, customer)).as("cart clear invisible before commit").isEqualTo(2);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            status.setRollbackOnly();
            return response;
        });
        assertThat(rolledBack).isNotNull();

        assertThat(number("select count(*) from orders")).isZero();
        assertThat(number("select count(*) from payments")).isZero();
        assertThat(number("select count(*) from order_items")).isZero();
        assertThat(number("select count(*) from event_outbox")).isZero();
        assertThat(number("select variant_stock from variants where id=?", variant)).isEqualTo(10);
        assertThat(cartQuantity(customer)).isEqualTo(2);

        OrderResponse committed = checkout(customer, PaymentMethod.COD);
        assertThat(number("select count(*) from orders")).isEqualTo(1);
        assertThat(number("select count(*) from event_outbox")).isEqualTo(1);
        assertThat(number("select variant_stock from variants where id=?", variant)).isEqualTo(8);
        assertThat(cartQuantity(customer)).isZero();
        assertPlacementFact(committed.orderId(), PaymentMethod.COD, "20.00");
        System.out.printf("Atomic placement: rollback removed every write; commit exposed one fact (%s)%n",
                variantId);
    }

    @Test
    void pausedOnlineCheckoutChangesNothingAndReturnsSafe409() throws Exception {
        long variant = variant("10.00", 10);
        long customer = customerWithCart(variant, 2);

        RuntimeException failure = catchRuntime(() -> pausedOnlineService.checkout(customer,
                new CartCheckoutRequest("fixture", "Fixture", "000", PaymentMethod.E_WALLET)));

        assertThat(failure).isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("online checkout is temporarily unavailable");
        assertThat(number("select variant_stock from variants where id=?", variant)).isEqualTo(10);
        assertThat(cartQuantity(customer)).isEqualTo(2);
        assertThat(number("select count(*) from orders")).isZero();
        assertThat(number("select count(*) from payments")).isZero();
        assertThat(number("select count(*) from event_outbox")).isZero();

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController(failure))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/task4-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(409))
                .andExpect(jsonPath("message").value("online checkout is temporarily unavailable"))
                .andExpect(jsonPath("data").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SQL"))));
        System.out.println("Paused online checkout: safe 409, cart/stock/order/outbox unchanged");
    }

    @Test
    void codRemainsEligibleWhileOnlineWorkPaused() throws Exception {
        long variant = variant("10.00", 10);
        long customer = customerWithCart(variant, 2);

        OrderResponse response = checkoutInTransaction(
                pausedOnlineService, customer, PaymentMethod.COD);

        assertThat(response.paymentExpiresAt()).isNull();
        assertThat(number("select count(*) from orders where customer_id=?", customer)).isEqualTo(1);
        assertThat(number("select count(*) from payments")).isEqualTo(1);
        assertThat(number("select count(*) from event_outbox")).isEqualTo(1);
        assertThat(number("select variant_stock from variants where id=?", variant)).isEqualTo(8);
        assertThat(cartQuantity(customer)).isZero();
        assertPlacementFact(response.orderId(), PaymentMethod.COD, "20.00");
        System.out.println("COD under pause: eligible, one order/payment/fact, stock=8, cart cleared");
    }

    @Test
    void codAndInactivePolicyKeepDeadlineNull() throws Exception {
        long codVariant = variant("10.00", 10);
        long codCustomer = customerWithCart(codVariant, 1);
        OrderResponse cod = checkout(codCustomer, PaymentMethod.COD);
        assertThat(cod.paymentExpiresAt()).isNull();
        assertThat(jdbc.queryForObject("select payment_expires_at from orders where order_id=?",
                Timestamp.class, cod.orderId())).isNull();
        assertPlacementFact(cod.orderId(), PaymentMethod.COD, "10.00");

        long inactiveVariant = variant("12.00", 10);
        long inactiveCustomer = customerWithCart(inactiveVariant, 1);
        OrderResponse inactive = checkoutInTransaction(
                inactivePolicyService, inactiveCustomer, PaymentMethod.E_WALLET);
        assertThat(inactive.paymentExpiresAt())
                .as("inactive policy must not invent a proposed legacy deadline").isNull();
        assertThat(jdbc.queryForObject("select payment_expires_at from orders where order_id=?",
                Timestamp.class, inactive.orderId())).isNull();
        assertPlacementFact(inactive.orderId(), PaymentMethod.E_WALLET, "12.00");
        System.out.println("Deadline boundary: COD null, inactive online null");
    }

    @Test
    void enabledPolicyUsesFifteenPostLockMinutes() throws Exception {
        long variant = variant("10.00", 10);
        long customer = customerWithCart(variant, 2);
        Instant before = clock();
        OrderResponse response = checkout(customer, PaymentMethod.E_WALLET);
        Instant after = clock();

        assertThat(response.paymentExpiresAt()).isNotNull();
        assertThat(response.paymentExpiresAt())
                .isBetween(before.plus(Duration.ofMinutes(15)), after.plus(Duration.ofMinutes(15)));
        assertThat(jdbc.queryForObject("select payment_expires_at from orders where order_id=?",
                Timestamp.class, response.orderId()).toInstant())
                .isEqualTo(response.paymentExpiresAt());
        assertPlacementFact(response.orderId(), PaymentMethod.E_WALLET, "20.00");
        System.out.println("Activated online deadline: database time + 15 minutes");
    }

    @Test
    void checkoutWritesOnlyTheTransactionalOutboxRow() {
        long variant = variant("10.00", 10);
        long customer = customerWithCart(variant, 1);
        OrderResponse response = checkout(customer, PaymentMethod.COD);

        assertThat(context.getBeanNamesForType(RabbitTemplate.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JavaMailSender.class)).isEmpty();
        assertThat(number("select count(*) from event_outbox where published_at is not null")).isZero();
        assertThat(number("select count(*) from event_outbox where lease_owner is not null")).isZero();
        assertThat(number("select count(*) from realtime_signal_outbox")).isZero();
        assertThat(jdbc.queryForMap("select published_at, quarantined_at, last_error_code "
                + "from event_outbox where order_id=(select id from orders where order_id=?)",
                response.orderId()))
                .containsEntry("published_at", null)
                .containsEntry("quarantined_at", null)
                .containsEntry("last_error_code", null);
        System.out.println("No provider IO: no broker/mail beans and only the unpublised outbox row");
    }

    private static void assertPlacementFact(String orderId, PaymentMethod method, String total)
            throws Exception {
        Map<String, Object> row = jdbc.queryForMap("""
                select o.event_sequence, e.order_sequence, e.event_type, e.payload::text as payload
                  from orders o
                  join event_outbox e on e.order_id = o.id
                 where o.order_id = ?
                """, orderId);
        assertThat(((Number) row.get("event_sequence")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("order_sequence")).longValue()).isEqualTo(1);
        assertThat(row.get("event_type")).isEqualTo("ORDER_PLACED");
        JsonNode payload = mapper.readTree((String) row.get("payload"));
        assertThat(payload.get("orderId").asString()).isEqualTo(orderId);
        assertThat(payload.get("orderSequence").asLong()).isEqualTo(1);
        assertThat(payload.get("orderStatus").asString()).isEqualTo(OrderStatus.PENDING.name());
        assertThat(payload.get("paymentStatus").asString()).isEqualTo(PaymentStatus.PENDING.name());
        assertThat(payload.get("paymentMethod").asString()).isEqualTo(method.name());
        assertThat(payload.get("total").asString()).isEqualTo(total);
        assertThat(payload.get("reason") == null || payload.get("reason").isNull()).isTrue();
        assertThat(payload.get("correlationId").asString()).isNotBlank()
                .hasSizeLessThanOrEqualTo(128);
        assertThat(payload.get("entryPoint").asString()).isNotBlank();
        assertThat(number("select count(*) from event_outbox where order_id="
                + "(select id from orders where order_id=?)", orderId)).isEqualTo(1);
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

    private static boolean isBoundedConflict(RuntimeException error) {
        return error instanceof org.springframework.dao.ConcurrencyFailureException
                || error instanceof jakarta.persistence.PessimisticLockException;
    }

    private static Outcome attempt(Supplier<Object> action) {
        try {
            return new Outcome(action.get(), null);
        } catch (RuntimeException failure) {
            return new Outcome(null, failure);
        }
    }

    private static RuntimeException catchRuntime(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static OrderResponse checkout(long customer, PaymentMethod method) {
        return service.checkout(customer,
                new CartCheckoutRequest("fixture", "Fixture", "000", method));
    }

    private static CartService serviceWith(ReservationPolicy policy) {
        CartService manual = new CartService(
                context.getBean(CustomerRepository.class),
                context.getBean(VariantRepository.class),
                context.getBean(CartRepository.class),
                context.getBean(CartItemRepository.class),
                context.getBean(CartMapper.class),
                context.getBean(OrderRepository.class),
                context.getBean(OrderItemRepository.class),
                context.getBean(PaymentRepository.class),
                context.getBean(OrderMapper.class),
                context.getBean(PaymentMapper.class),
                policy,
                context.getBean(OrderLifecycleService.class),
                context.getBean(DatabaseClock.class),
                context.getBean(CompatibilityService.class));
        ReflectionTestUtils.setField(manual, "transactionLockBounds", Boolean.FALSE);
        return manual;
    }

    private static OrderResponse checkoutInTransaction(CartService target, long customer,
                                                       PaymentMethod method) {
        return transaction.execute(status -> target.checkout(customer,
                new CartCheckoutRequest("fixture", "Fixture", "000", method)));
    }

    private static long customerWithCart(long variant, int quantity) {
        long customer = customer();
        addItem(cart(customer), variant, quantity);
        return customer;
    }

    private static long customer() {
        String suffix = UUID.randomUUID().toString();
        long role = jdbc.queryForObject("""
                insert into roles(role_id, role_code, role_name)
                values (?, ?, 'Fixture') returning id
                """, Long.class, "ROL_" + suffix, suffix);
        long user = jdbc.queryForObject("""
                insert into users(user_id, user_email, user_password_hash, role_id)
                values (?, ?, 'unused', ?) returning id
                """, Long.class, "USR_" + suffix, suffix + "@example.invalid", role);
        return jdbc.queryForObject("""
                insert into customers(customer_id, user_id, customer_name, customer_phone)
                values (?, ?, 'Fixture', '000') returning id
                """, Long.class, "CUS_" + suffix, user);
    }

    private static long variant(String price, int stock) {
        String suffix = UUID.randomUUID().toString();
        long product = jdbc.queryForObject("""
                insert into products(product_id, product_name, product_description,
                                     product_status, product_slug)
                values (?, 'Fixture', 'fixture', 'ACTIVE', ?) returning id
                """, Long.class, "PRD_" + suffix, "fixture-" + suffix);
        return jdbc.queryForObject("""
                insert into variants(variant_id, product_id, variant_sku, variant_price, variant_stock)
                values (?, ?, ?, ?, ?) returning id
                """, Long.class, "VAR_" + suffix, product, suffix,
                new BigDecimal(price), stock);
    }

    private static String variantBusinessId(long variant) {
        return jdbc.queryForObject("select variant_id from variants where id=?", String.class, variant);
    }

    private static long cart(long customer) {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                insert into carts(cart_id, customer_id) values (?, ?) returning id
                """, Long.class, "CRT_" + suffix, customer);
    }

    private static void addItem(long cart, long variant, int quantity) {
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
                insert into cart_items(cart_item_id, cart_id, variant_id, quantity)
                values (?, ?, ?, ?)
                """, "ITM_" + suffix, cart, variant, quantity);
    }

    private static long cartQuantity(long customer) {
        return number("""
                select coalesce(sum(i.quantity), 0)
                  from cart_items i join carts c on c.id = i.cart_id
                 where c.customer_id = ?
                """, customer);
    }

    private static long number(String sql, Object... parameters) {
        Long value = jdbc.queryForObject(sql, Long.class, parameters);
        return value == null ? 0 : value;
    }

    /** Reads committed state on a separate connection so the caller's writes stay invisible. */
    private static long observedNumber(String sql, Object... parameters) throws Exception {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static Instant clock() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    private static Instant clock(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select clock_timestamp()")) {
            assertThat(rows.next()).isTrue();
            return rows.getTimestamp(1).toInstant();
        }
    }

    private static int pid() {
        return ((Number) entityManager.createNativeQuery("select pg_backend_pid()")
                .getSingleResult()).intValue();
    }

    private static String awaitBlocked(Connection observer, int holderPid,
                                       AtomicInteger waiterPid, Future<?> waiter) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (PreparedStatement statement = observer.prepareStatement(
                    "select query from pg_stat_activity where pid = ? "
                            + "and wait_event_type = 'Lock' and ? = any(pg_blocking_pids(pid))")) {
                statement.setInt(1, waiterPid.get());
                statement.setInt(2, holderPid);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) return rows.getString(1);
                }
            }
            assertThat(waiter.isDone()).as("loser cannot finish before winning transaction commits")
                    .isFalse();
        }
        throw new AssertionError("PostgreSQL did not observe the checkout waiting on the variant lock");
    }

    private static Connection connect() throws Exception {
        return pool.getConnection();
    }

    private static int backendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select pg_backend_pid()")) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
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
        System.out.println("Verified Task4 scratch identity: " + nameOf(owned));
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
        System.out.println("Confirmed Task4 scratch cleanup: " + nameOf(owned));
    }

    private record Outcome(Object value, RuntimeException error) {
        private boolean isSuccess() {
            return error == null;
        }
    }

    private record Race(Outcome first, Outcome second) { }

    @RestController
    static class FailureController {
        private final RuntimeException failure;
        FailureController(RuntimeException failure) { this.failure = failure; }
        @GetMapping("/task4-conflict")
        Object fail() { throw failure; }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = "com.project.dacn.repository")
    @Import({CartService.class, OrderLifecycleService.class,
            OrderResponseAssembler.class, CompatibilityService.class})
    static class Config {
        @Bean
        CartMapper cartMapper() {
            return org.mapstruct.factory.Mappers.getMapper(CartMapper.class);
        }

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
        ReservationPolicy reservationPolicy() {
            return new ReservationPolicy(true, false, false);
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
