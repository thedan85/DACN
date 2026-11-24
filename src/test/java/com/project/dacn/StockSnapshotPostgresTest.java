package com.project.dacn;

import com.project.dacn.dto.request.catalog.StockSnapshotRequest;
import com.project.dacn.dto.response.catalog.StockSnapshotResponse;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.catalog.VariantRepository.ProductStockView;
import com.project.dacn.service.catalog.StockSnapshotService;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL proof for the public stock snapshot: the exact Task 11
 * visibility predicate, zero-variant products, query bounds, and projection
 * agreement, on an invocation-owned scratch database.
 */
class StockSnapshotPostgresTest {

    private static ExternalPostgresDatabase database;
    private static String ownedName;
    private static HikariDataSource pool;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static VariantRepository variantRepository;
    private static StockSnapshotService service;
    private static Statistics statistics;

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
            hikari.setPoolName("task12-stock-snapshot-pg");
            pool = new HikariDataSource(hikari);
            DataSource source = pool;
            context = new AnnotationConfigApplicationContext();
            context.registerBean(DataSource.class, () -> source);
            context.register(Config.class);
            context.refresh();
            jdbc = context.getBean(JdbcTemplate.class);
            variantRepository = context.getBean(VariantRepository.class);
            service = context.getBean(StockSnapshotService.class);
            statistics = context.getBean(EntityManagerFactory.class)
                    .unwrap(SessionFactory.class).getStatistics();
            assertThat(jdbc.queryForObject("""
                    select count(*) from flyway_schema_history
                     where version in ('34', '35', '36') and success
                    """, Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("select current_database()", String.class))
                    .isEqualTo(ownedName);
            System.out.println("Task12 PostgreSQL evidence: Flyway V34/V35/V36 successful, "
                    + "scratch identity verified");
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
    void visibilityOmitsHiddenAndUnknownRowsAndKeepsZeroVariantProducts() {
        String active = product("ACTIVE");
        String zeroVariant = product("ACTIVE");
        String draft = product("DRAFT");
        String deleted = product("DELETED");
        String visible = variant(active, 3, false);
        jdbc.update("update variants set variant_stock = 8 where variant_id = ?", visible);
        String deletedThenRestored = variant(active, 5, true);
        jdbc.update("update variants set variant_stock = variant_stock + 4 where variant_id = ?",
                deletedThenRestored);
        String draftVariant = variant(draft, 6, false);
        String deletedProductVariant = variant(deleted, 7, false);

        StockSnapshotResponse response = service.snapshots(new StockSnapshotRequest(
                List.of(active, zeroVariant, draft, deleted, "PRD_missing"),
                List.of(visible, deletedThenRestored, draftVariant, deletedProductVariant,
                        "VAR_missing")));

        assertThat(response.products())
                .extracting(StockSnapshotResponse.ProductStock::productId)
                .containsExactlyInAnyOrder(active, zeroVariant);
        assertThat(response.products())
                .filteredOn(row -> row.productId().equals(active))
                .singleElement()
                .extracting(StockSnapshotResponse.ProductStock::stock)
                .isEqualTo(8L);
        assertThat(response.products())
                .filteredOn(row -> row.productId().equals(zeroVariant))
                .singleElement()
                .extracting(StockSnapshotResponse.ProductStock::stock)
                .isEqualTo(0L);

        assertThat(response.variants()).singleElement().satisfies(row -> {
            assertThat(row.variantId()).isEqualTo(visible);
            assertThat(row.productId()).isEqualTo(active);
            assertThat(row.stock()).isEqualTo(8);
            assertThat(row.stockRevision()).isEqualTo(1L);
        });
        System.out.println("Task12 PostgreSQL visibility: ACTIVE/DRAFT/DELETED, deleted+restored "
                + "variant, zero-variant, unknown ids passed");
    }

    @Test
    void deletedThenStockRestoredHistoricalVariantStaysOmitted() {
        String active = product("ACTIVE");
        String visible = variant(active, 2, false);
        String deletedThenRestored = variant(active, 5, true);
        jdbc.update("update variants set variant_stock = variant_stock + 4 where variant_id = ?",
                deletedThenRestored);

        StockSnapshotResponse response = service.snapshots(new StockSnapshotRequest(
                List.of(active), List.of(deletedThenRestored, visible)));

        assertThat(response.products()).singleElement()
                .extracting(StockSnapshotResponse.ProductStock::stock)
                .isEqualTo(2L);
        assertThat(response.variants()).singleElement()
                .extracting(StockSnapshotResponse.VariantStock::variantId)
                .isEqualTo(visible);
        System.out.println("Task12 PostgreSQL historical variant: deleted stock+4 release stays "
                + "omitted from product and variant rows");
    }

    @Test
    void snapshotUsesAtMostTwoPreparedStatementsPerRequest() {
        String active = product("ACTIVE");
        String zeroVariant = product("ACTIVE");
        String visible = variant(active, 4, false);

        StockSnapshotResponse mixed = measureAtMost(2, () -> service.snapshots(
                new StockSnapshotRequest(List.of(active, zeroVariant),
                        List.of(visible, "VAR_missing"))));
        assertThat(mixed.products()).hasSize(2);
        assertThat(mixed.variants()).singleElement();

        StockSnapshotResponse productOnly = measureAtMost(1, () -> service.snapshots(
                new StockSnapshotRequest(List.of(active, zeroVariant), List.of())));
        assertThat(productOnly.products()).hasSize(2);
        assertThat(productOnly.variants()).isEmpty();

        StockSnapshotResponse variantOnly = measureAtMost(1, () -> service.snapshots(
                new StockSnapshotRequest(List.of(), List.of(visible, "VAR_missing"))));
        assertThat(variantOnly.products()).isEmpty();
        assertThat(variantOnly.variants()).singleElement();
        System.out.println("Task12 PostgreSQL query bounds: mixed=2, product-only=1, "
                + "variant-only=1 prepared statements");
    }

    @Test
    void productProjectionsAgreeForProductsWithVariantsAndSnapshotAddsZeroVariantRows() {
        String active = product("ACTIVE");
        String zeroVariant = product("ACTIVE");
        String draft = product("DRAFT");
        String firstVariant = variant(active, 4, false);
        String secondVariant = variant(active, 6, false);
        variant(active, 9, true);
        variant(draft, 7, false);
        List<String> requested = List.of(active, zeroVariant, draft, "PRD_missing");

        List<ProductStockView> catalog = variantRepository.findStockByProductIdIn(requested);
        List<ProductStockView> snapshot = variantRepository.findSnapshotStockByProductIdIn(requested);

        assertThat(catalog).singleElement().satisfies(row -> {
            assertThat(row.getProductId()).isEqualTo(active);
            assertThat(row.getTotalStock()).isEqualTo(10L);
        });
        assertThat(snapshot)
                .extracting(ProductStockView::getProductId)
                .containsExactlyInAnyOrder(active, zeroVariant);
        assertThat(snapshot).filteredOn(row -> row.getProductId().equals(active))
                .singleElement()
                .satisfies(row -> assertThat(row.getTotalStock()).isEqualTo(10L));
        assertThat(snapshot).filteredOn(row -> row.getProductId().equals(zeroVariant))
                .singleElement()
                .satisfies(row -> assertThat(row.getTotalStock()).isZero());
        assertThat(variantRepository.findStockByVariantIdIn(
                List.of(firstVariant, secondVariant)))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getProductId()).isEqualTo(active));
        System.out.println("Task12 PostgreSQL projection agreement: catalog=10, snapshot=10 plus "
                + "zero-variant 0; hidden rows absent");
    }

    private static StockSnapshotResponse measureAtMost(
            int expected, java.util.function.Supplier<StockSnapshotResponse> call) {
        statistics.clear();
        StockSnapshotResponse response = call.get();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(expected);
        return response;
    }

    private static String product(String status) {
        String suffix = UUID.randomUUID().toString();
        String id = "PRD_" + suffix;
        jdbc.update("""
                insert into products(product_id, product_name, product_description,
                                     product_status, product_slug)
                values (?, 'Fixture', 'fixture', ?, ?)
                """, id, status, "fixture-" + suffix);
        return id;
    }

    private static String variant(String productId, int stock, boolean deleted) {
        String suffix = UUID.randomUUID().toString();
        String id = "VAR_" + suffix;
        jdbc.update("""
                insert into variants(variant_id, product_id, variant_sku, variant_price,
                                     variant_stock, is_deleted)
                select ?, p.id, ?, 1.00, ?, ? from products p where p.product_id = ?
                """, id, "SKU_" + suffix, stock, deleted, productId);
        return id;
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
        System.out.println("Verified Task12 scratch identity: " + nameOf(owned));
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
        System.out.println("Confirmed Task12 scratch cleanup: " + nameOf(owned));
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = "com.project.dacn.repository")
    @Import(StockSnapshotService.class)
    static class Config {
        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.project.dacn.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "none",
                    "hibernate.show_sql", "false",
                    "hibernate.generate_statistics", "true"));
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
