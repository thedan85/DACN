package com.project.dacn.repository.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.integration.payment.vnpay.VnpayProperties;
import com.project.dacn.integration.payment.vnpay.VnpaySignatureService;
import com.project.dacn.integration.payment.vnpay.VnpayUrlBuilder;
import com.project.dacn.service.order.ReservationPolicy;
import com.project.dacn.support.ExternalPostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.project.dacn.repository.order.LegacyReservationQueries.Disposition.*;
import static com.project.dacn.repository.order.LegacyReservationQueries.EvidenceAvailability.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyReservationQueriesPostgresTest {

    // Columns follow the actual PaymentStatus enum: PENDING, CONFIRMED, FAILED, REFUNDED, REFUND_REQUIRED.
    private static final Map<OrderStatus, List<LegacyReservationQueries.Disposition>> ONLINE_DISPOSITIONS = Map.of(
            OrderStatus.PENDING, List.of(PENDING_RECONCILIATION, PRESERVE_RECEIVED_MONEY, QUARANTINE,
                    QUARANTINE, PRESERVE_RECEIVED_MONEY),
            OrderStatus.CONFIRMED, List.of(BLOCK_ACTIVATION, PRESERVE_RECEIVED_MONEY, BLOCK_ACTIVATION,
                    QUARANTINE, PRESERVE_RECEIVED_MONEY),
            OrderStatus.SHIPPING, List.of(QUARANTINE, PRESERVE_RECEIVED_MONEY, QUARANTINE,
                    QUARANTINE, PRESERVE_RECEIVED_MONEY),
            OrderStatus.COMPLETED, List.of(QUARANTINE, PRESERVE_RECEIVED_MONEY, QUARANTINE,
                    QUARANTINE, PRESERVE_RECEIVED_MONEY),
            OrderStatus.CANCELLED, List.of(QUARANTINE, PRESERVE_RECEIVED_MONEY, QUARANTINE,
                    QUARANTINE, PRESERVE_RECEIVED_MONEY));

    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static AnnotationConfigApplicationContext context;
    private static SingleConnectionDataSource source;
    private static JdbcTemplate jdbc;
    private static LegacyReservationQueries queries;
    private static LegacyReservationQueries.Inventory snapshot;
    private static Map<String, String> originalTables;

    @BeforeAll
    static void seedOnlyIdentityVerifiedOwnedScratch() throws Exception {
        database = ExternalPostgresDatabase.create();
        ownedName = nameOf(database);
        try {
            assertIdentity(database, ownedName);
            database.migrate();
            source = new SingleConnectionDataSource(database.jdbcUrl(), database.username(), database.password(), true);
            jdbc = new JdbcTemplate(source);
            assertIdentity(database, ownedName);
            seed();
            // No Boot/Flyway/SQL-init auto-configuration can inherit a separate application URL.
            context = new AnnotationConfigApplicationContext();
            context.registerBean(DataSource.class, () -> source);
            context.register(Config.class);
            context.refresh();
            queries = context.getBean(LegacyReservationQueries.class);
            originalTables = tableFingerprints();
            snapshot = queries.inventory();
        } catch (Exception | AssertionError failure) {
            cleanup();
            throw failure;
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (context != null) {
            context.close();
            context = null;
        }
        if (source != null) {
            source.destroy();
            source = null;
        }
        if (database != null) {
            dropAndVerify(database, ownedName);
            database = null;
        }
    }

    @Test
    void inventoryIncludesEveryActualEnumTripleAndMissingRowsWithoutJoinMultiplication() {
        var inventory = queries.inventory();
        assertThat(inventory.orders()).hasSize(133);
        assertThat(inventory.countsByPair()).hasSize(127);
        assertThat(inventory.countsByPair().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(133);
        Map<LegacyReservationQueries.StatusPair, Long> additionalCounts = Map.of(
                new LegacyReservationQueries.StatusPair(OrderStatus.PENDING, PaymentMethod.BANK_TRANSFER, PaymentStatus.PENDING), 4L,
                new LegacyReservationQueries.StatusPair(OrderStatus.PENDING, PaymentMethod.E_WALLET, PaymentStatus.PENDING), 2L,
                new LegacyReservationQueries.StatusPair(OrderStatus.CONFIRMED, PaymentMethod.BANK_TRANSFER, PaymentStatus.PENDING), 2L,
                new LegacyReservationQueries.StatusPair(OrderStatus.CANCELLED, PaymentMethod.BANK_TRANSFER, PaymentStatus.FAILED), 2L);
        for (OrderStatus order : OrderStatus.values()) {
            for (PaymentMethod method : PaymentMethod.values()) {
                for (PaymentStatus payment : PaymentStatus.values()) {
                    var row = evidence("ORD_T2_" + order + "_" + method + "_" + payment);
                    assertThat(row.orderStatus()).isEqualTo(order);
                    assertThat(row.paymentMethod()).isEqualTo(method);
                    assertThat(row.paymentStatus()).isEqualTo(payment);
                    assertThat(row.itemCount()).isEqualTo(2);
                    assertThat(row.itemQuantity()).isEqualTo(5);
                    var pair = new LegacyReservationQueries.StatusPair(order, method, payment);
                    assertThat(inventory.countsByPair()).containsEntry(pair, additionalCounts.getOrDefault(pair, 1L));
                    assertThat(row.disposition()).isEqualTo(method == PaymentMethod.COD
                            ? COD_UNCHANGED : ONLINE_DISPOSITIONS.get(order).get(payment.ordinal()));
                }
            }
        }
        var missingPayment = evidence("ORD_T2_MISSING_PAYMENT");
        assertThat(missingPayment.paymentId()).isNull();
        assertThat(missingPayment.paymentMethod()).isNull();
        assertThat(missingPayment.paymentStatus()).isNull();
        assertThat(missingPayment.disposition()).isEqualTo(QUARANTINE);
        assertThat(evidence("ORD_T2_MISSING_ITEMS").itemCount()).isZero();
        assertThat(evidence("ORD_T2_MISSING_ITEMS").disposition()).isEqualTo(QUARANTINE);
        assertThat(evidence("ORD_T2_MISSING_BOTH").itemCount()).isZero();
        assertThat(evidence("ORD_T2_MISSING_BOTH").paymentId()).isNull();
        assertThat(inventory.countsByPair()).containsEntry(new LegacyReservationQueries.StatusPair(
                OrderStatus.PENDING, PaymentMethod.BANK_TRANSFER, PaymentStatus.PENDING), 4L);
        assertThat(inventory.countsByPair()).containsEntry(new LegacyReservationQueries.StatusPair(
                OrderStatus.PENDING, null, null), 1L).containsEntry(new LegacyReservationQueries.StatusPair(
                OrderStatus.COMPLETED, null, null), 1L);
        assertThat(inventory.orders()).filteredOn(row -> row.disposition() == COD_UNCHANGED).hasSize(25);
        assertThat(inventory.orders()).filteredOn(row -> row.disposition() == PRESERVE_RECEIVED_MONEY).hasSize(40);
        assertThat(inventory.orders()).filteredOn(row -> row.disposition() == PENDING_RECONCILIATION).hasSize(7);
        assertThat(inventory.orders()).filteredOn(row -> row.disposition() == BLOCK_ACTIVATION).hasSize(9);
        assertThat(inventory.orders()).filteredOn(row -> row.disposition() == QUARANTINE).hasSize(52);
    }

    @Test
    void confirmedUnpaidBlocksActivationAndCompletedUnpaidStaysQuarantinedWithoutInventingMoney() {
        assertThat(queries.inventory().unresolvedConfirmedUnpaidCount()).isEqualTo(9);
        for (PaymentMethod method : PaymentMethod.values()) {
            var confirmed = evidence("ORD_T2_CONFIRMED_" + method + "_PENDING");
            var completed = evidence("ORD_T2_COMPLETED_" + method + "_PENDING");
            if (method == PaymentMethod.COD) {
                assertThat(confirmed.blocksActivation()).isFalse();
                assertThat(confirmed.disposition()).isEqualTo(COD_UNCHANGED);
                assertThat(completed.disposition()).isEqualTo(COD_UNCHANGED);
            } else {
                assertThat(confirmed.blocksActivation()).isTrue();
                assertThat(confirmed.disposition()).isEqualTo(BLOCK_ACTIVATION);
                assertThat(completed.disposition()).isEqualTo(QUARANTINE);
                assertThat(completed.paymentExpiresAt()).isNull();
            }
            assertThat(completed.fulfilmentEvidence()).isEqualTo(UNKNOWN);
        }
        var conflict = evidence("ORD_T2_CONFLICTING_EVIDENCE");
        assertThat(conflict.providerTransactionId()).isEqualTo("fixture-provider-conflict");
        assertThat(conflict.paidAt()).isNotNull();
        assertThat(conflict.blocksActivation()).isTrue();
        assertThat(conflict.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void receivedMoneyAndRefundStatesKeepRestrictedEvidenceEvenOnCancelledOrCompletedOrders() {
        for (OrderStatus order : OrderStatus.values()) {
            for (PaymentMethod method : PaymentMethod.values()) {
                for (PaymentStatus payment : List.of(PaymentStatus.CONFIRMED, PaymentStatus.REFUND_REQUIRED,
                        PaymentStatus.REFUNDED)) {
                    var row = evidence("ORD_T2_" + order + "_" + method + "_" + payment);
                    assertThat(row.providerTransactionId()).isNotBlank();
                    assertThat(row.paidAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
                    assertThat(row.paymentStatus()).isEqualTo(payment);
                    assertThat(row.blocksActivation()).isFalse();
                    if (method != PaymentMethod.COD) {
                        assertThat(row.disposition()).isEqualTo(payment == PaymentStatus.REFUNDED
                                ? QUARANTINE : PRESERVE_RECEIVED_MONEY);
                    }
                }
            }
        }
    }

    @Test
    void releaseAndFulfilmentHistoryStayUnknownUnlessAnExplicitReleaseMarkerExists() {
        var cancelled = evidence("ORD_T2_CANCELLED_BANK_TRANSFER_FAILED");
        assertThat(cancelled.stockReleasedAt()).isNull();
        assertThat(cancelled.releaseEvidence()).isEqualTo(UNKNOWN);
        assertThat(cancelled.disposition()).isEqualTo(QUARANTINE);
        assertThat(cancelled.fulfilmentEvidence()).isEqualTo(UNKNOWN);
        var released = evidence("ORD_T2_RELEASE_RECORDED");
        assertThat(released.releaseEvidence()).isEqualTo(RECORDED);
        assertThat(released.stockReleasedAt()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(released.cancellationReason()).isEqualTo("STAFF_REJECTED");
        assertThat(released.disposition()).isEqualTo(QUARANTINE);
        assertThat(evidence("ORD_T2_COMPLETED_BANK_TRANSFER_PENDING").fulfilmentEvidence()).isEqualTo(UNKNOWN);
    }

    @Test
    void inactiveOldNullDeadlineAndProposedBatchesRemainEvidenceNotEffectiveCutoffDecisions() {
        var inactive = new ReservationPolicy(false, false, false);
        assertThat(inactive.isActive()).isFalse();
        var old = evidence("ORD_T2_OLD_URL");
        assertThat(old.createdAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(old.paymentExpiresAt()).isNull();
        assertThat(old.latestPossibleIssuedUrlExpiry()).isNull();
        assertThat(old.issuedUrlEvidence()).isEqualTo(UNKNOWN);

        VnpayProperties properties = new VnpayProperties();
        properties.setHashSecret("owned-fixture-not-a-merchant-secret");
        properties.setPayUrl("https://provider.invalid/pay");
        properties.setReturnUrl("http://127.0.0.1/fixture-return");
        var signatures = new VnpaySignatureService(properties);
        String url = new VnpayUrlBuilder(properties, signatures).build(old.orderId(), 10, "fixture", "127.0.0.1");
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        assertThat(signatures.verifyParams(params)).isTrue();
        Instant urlExpiry = LocalDateTime.parse(params.get("vnp_ExpireDate"),
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        assertThat(urlExpiry).isAfter(Instant.now());
        assertThat(evidence("ORD_T2_OLD_URL").latestPossibleIssuedUrlExpiry()).isNull();
        var past = evidence("ORD_T2_PROPOSED_PAST");
        var future = evidence("ORD_T2_PROPOSED_FUTURE");
        assertThat(past.paymentExpiresAt()).isBefore(Instant.now());
        assertThat(future.paymentExpiresAt()).isAfter(Instant.now());
        assertThat(past.disposition()).isEqualTo(PENDING_RECONCILIATION);
        assertThat(future.disposition()).isEqualTo(PENDING_RECONCILIATION);
        assertThat(old.disposition()).isEqualTo(PENDING_RECONCILIATION);
        // Runtime callback/URL/claim guards are deliberately left to Tasks 4/6/8/9.
        assertThat(tableFingerprints()).isEqualTo(originalTables);
    }

    @Test
    void inventoryRunsInServerEnforcedReadOnlyTransactionAndChangesNoPublicTable() {
        var transaction = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        transaction.setReadOnly(true);
        transaction.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("show transaction_read_only", String.class)).isEqualTo("on");
            assertThat(queries.inventory().orders()).hasSize(133);
        });
        assertThat(tableFingerprints()).isEqualTo(originalTables);
    }

    @Test
    void failedFixtureSetupStillDropsOnlyItsInvocationOwnedDatabase() throws Exception {
        var failed = ExternalPostgresDatabase.create();
        String failedName = nameOf(failed);
        try {
            assertIdentity(failed, failedName);
            assertThat(failedName).isNotEqualTo(ownedName);
            var failedJdbc = new JdbcTemplate(new DriverManagerDataSource(
                    failed.jdbcUrl(), failed.username(), failed.password()));
            assertThatThrownBy(() -> failedJdbc.update("insert into task2_missing_fixture_table values (?)", 1))
                    .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);
        } finally {
            dropAndVerify(failed, failedName);
        }
        assertIdentity(database, ownedName);
    }

    private static LegacyReservationQueries.OrderEvidence evidence(String id) {
        return snapshot.orders().stream().filter(row -> row.orderId().equals(id))
                .findFirst().orElseThrow();
    }

    private static void seed() {
        jdbc.update("insert into roles (role_id, role_code, role_name) values (?, ?, ?)",
                "ROLE_task2_fixture", "task2_fixture", "Fixture");
        jdbc.update("insert into users (user_id, user_email, user_password_hash, role_id) "
                        + "select ?, ?, ?, id from roles where role_id = ?",
                "USR_task2_fixture", "task2-fixture@example.invalid", "unused", "ROLE_task2_fixture");
        jdbc.update("insert into customers (customer_id, user_id, customer_name, customer_phone) "
                        + "select ?, id, ?, ? from users where user_id = ?",
                "CUS_task2_fixture", "Fixture", "000", "USR_task2_fixture");
        for (OrderStatus order : OrderStatus.values()) {
            for (PaymentMethod method : PaymentMethod.values()) {
                for (PaymentStatus payment : PaymentStatus.values()) {
                    addOrder("ORD_T2_" + order + "_" + method + "_" + payment, order, method, payment, true);
                }
            }
        }
        addOrder("ORD_T2_MISSING_PAYMENT", OrderStatus.PENDING, null, null, true);
        addOrder("ORD_T2_MISSING_ITEMS", OrderStatus.PENDING, PaymentMethod.BANK_TRANSFER, PaymentStatus.PENDING, false);
        addOrder("ORD_T2_OLD_URL", OrderStatus.PENDING, PaymentMethod.BANK_TRANSFER, PaymentStatus.PENDING, true);
        addOrder("ORD_T2_PROPOSED_PAST", OrderStatus.PENDING, PaymentMethod.BANK_TRANSFER, PaymentStatus.PENDING, true);
        addOrder("ORD_T2_PROPOSED_FUTURE", OrderStatus.PENDING, PaymentMethod.E_WALLET, PaymentStatus.PENDING, true);
        addOrder("ORD_T2_RELEASE_RECORDED", OrderStatus.CANCELLED, PaymentMethod.BANK_TRANSFER, PaymentStatus.FAILED, true);
        addOrder("ORD_T2_CONFLICTING_EVIDENCE", OrderStatus.CONFIRMED, PaymentMethod.BANK_TRANSFER, PaymentStatus.PENDING, true);
        addOrder("ORD_T2_MISSING_BOTH", OrderStatus.COMPLETED, null, null, false);
        jdbc.update("update orders set payment_expires_at = ?::timestamptz where order_id = ?",
                "2026-10-01T00:15:00Z", "ORD_T2_PROPOSED_PAST");
        jdbc.update("update orders set payment_expires_at = clock_timestamp() + interval '1 day' where order_id = ?",
                "ORD_T2_PROPOSED_FUTURE");
        jdbc.update("update orders set stock_released_at = ?::timestamptz, cancellation_reason = ? where order_id = ?",
                "2026-10-02T00:00:00Z", "STAFF_REJECTED", "ORD_T2_RELEASE_RECORDED");
        jdbc.update("update payments set paid_at = ?::timestamptz, payment_transaction_id = ? "
                        + "where order_id = (select id from orders where order_id = ?)",
                "2026-10-01T00:00:00Z", "fixture-provider-conflict", "ORD_T2_CONFLICTING_EVIDENCE");
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isEqualTo(133);
    }

    private static void addOrder(String id, OrderStatus order, PaymentMethod method,
                                 PaymentStatus payment, boolean items) {
        jdbc.update("insert into orders (order_id, customer_id, order_status, order_total, order_address, "
                        + "order_recipient_name, order_phone, created_at) select ?, id, ?, 10, ?, ?, ?, "
                        + "?::timestamptz from customers where customer_id = ?",
                id, order.name(), "fixture", "Fixture", "000", "2026-10-01T00:00:00Z", "CUS_task2_fixture");
        if (method != null) {
            boolean received = payment == PaymentStatus.CONFIRMED || payment == PaymentStatus.REFUND_REQUIRED
                    || payment == PaymentStatus.REFUNDED;
            jdbc.update("insert into payments (payment_id, order_id, payment_method, payment_status, payment_amount, "
                            + "payment_transaction_id, paid_at) select ?, id, ?, ?, 10, ?, ?::timestamptz "
                            + "from orders where order_id = ?",
                    "PAY_" + id, method.name(), payment.name(), received ? "fixture-provider-" + id : null,
                    received ? "2026-10-01T00:00:00Z" : null, id);
        }
        if (items) {
            for (int quantity : List.of(2, 3)) {
                jdbc.update("insert into order_items (order_id, variant_id, quantity, unit_price) "
                                + "select id, (select min(id) from variants), ?, 2 from orders where order_id = ?",
                        quantity, id);
            }
        }
    }

    private static Map<String, String> tableFingerprints() {
        var result = new LinkedHashMap<String, String>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname = 'public' "
                + "order by tablename", String.class)) {
            // Only catalog-owned identifiers enter this test-only snapshot SQL.
            assertThat(table).matches("[a-z_][a-z0-9_]*");
            result.put(table, jdbc.queryForObject("select md5(coalesce(string_agg(row_data, ',' order by row_data), '')) "
                    + "from (select row_to_json(t)::text as row_data from public.\"" + table + "\" t) rows", String.class));
        }
        return result;
    }

    private static String nameOf(ExternalPostgresDatabase owned) {
        return URI.create(owned.jdbcUrl().substring("jdbc:".length())).getPath().substring(1);
    }

    private static void assertIdentity(ExternalPostgresDatabase owned, String name) throws Exception {
        assertThat(name).matches("verify_event_foundation_[0-9a-f]{12}");
        try (Connection connection = DriverManager.getConnection(owned.jdbcUrl(), owned.username(), owned.password());
             PreparedStatement statement = connection.prepareStatement("select current_database()");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(name);
        }
        System.out.println("Verified legacy inventory scratch identity: " + name);
    }

    private static void dropAndVerify(ExternalPostgresDatabase owned, String name) throws Exception {
        owned.drop();
        try (Connection connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv("TEST_POSTGRES_USERNAME"), System.getenv("TEST_POSTGRES_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement("select 1 from pg_database where datname = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("exact invocation-owned scratch database is gone").isFalse();
            }
        }
        System.out.println("Confirmed legacy inventory scratch cleanup: " + name);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(LegacyReservationQueries.class)
    static class Config {
        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            var manager = new DataSourceTransactionManager(source);
            manager.setEnforceReadOnly(true);
            return manager;
        }
    }
}
