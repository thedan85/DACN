package com.project.dacn.performance;

import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.config.ProductSortConverter;
import com.project.dacn.config.SecurityConfig;
import com.project.dacn.controller.cart.CartController;
import com.project.dacn.controller.catalog.ProductController;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.messaging.outbox.OrderEventOutbox;
import com.project.dacn.security.AuthenticationEntryPoint;
import com.project.dacn.security.CustomAccessDeniedHandler;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.CustomerIdentity;
import com.project.dacn.security.JwtAuthenticationFilter;
import com.project.dacn.security.JwtService;
import com.project.dacn.service.cart.CartService;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.ProductService;
import com.project.dacn.service.engagement.ReviewService;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small, repeatable local HTTP baseline, not the gated hosted 100/200-client run.
 * Removing a stock deduction, returning rejected checkouts, or losing an order must fail this test.
 * Both phases use the same fixture and workload; phase changes only the evidence label.
 */
class InventoryPerformanceTest {

    private static final int CLIENTS = 2;
    private static final int PRODUCTS = 16;
    private static final int INITIAL_STOCK = 1000;
    private static final int QUANTITY = 2;
    private static final int WARMUP_PER_CLIENT = 2;
    private static final int BATCHES = 3;
    private static final int SAMPLES_PER_CLIENT_PER_BATCH = 10;
    private static final int CHECKOUTS = 64;
    private static final long MAX_FIXTURE_BYTES = 50_000_000L;
    private static final String PREFIX = "inventory-perf-";
    private static final String CHECKOUT_BODY = """
            {"address":"Fixture only","recipientName":"Fixture","phone":"0000000000","method":"COD"}
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final Set<String> orders = ConcurrentHashMap.newKeySet();
    private final List<Map<String, Object>> measurements = new ArrayList<>();

    @Test
    void measureSuccessfulCheckoutAndCatalogAgainstOwnedPostgres() throws Exception {
        String phase = System.getProperty("inventory.perf.phase", "baseline");
        assertThat(phase).isIn("baseline", "after");
        ExternalPostgresDatabase database = null;
        HikariDataSource pool = null;
        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("phase", phase);
        evidence.put("workload", "local-loopback-http-cod-v1");
        evidence.put("clients", CLIENTS);
        evidence.put("warmupPerClient", WARMUP_PER_CLIENT);
        evidence.put("batches", BATCHES);
        evidence.put("samplesPerClientPerBatch", SAMPLES_PER_CLIENT_PER_BATCH);
        evidence.put("cache", "Redis bypassed; Hibernate L2/query cache off; JVM/DB warmed, no server cache flush");
        evidence.put("checkoutP95TargetMs", 1500);
        evidence.put("maximumRegressionPercent", 10);
        evidence.put("measurements", measurements);
        try {
            database = ExternalPostgresDatabase.create();
            String ownedName = ownedName(database);
            evidence.put("ownedDatabase", ownedName);
            verifyIdentity(database, ownedName);
            database.migrate();
            pool = ownedPool(database);
            HikariDataSource ownedPool = pool;
            new SpringApplicationBuilder(Fixture.class)
                    .environment(isolatedEnvironment(database))
                    .web(WebApplicationType.SERVLET)
                    .registerShutdownHook(false)
                    .initializers(application -> {
                        context.set(application);
                        application.getBeanFactory().registerSingleton("dataSource", ownedPool);
                    })
                    .run();
            ConfigurableApplicationContext app = context.get();
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(ownedName);
            assertThat(app.getBeansOfType(javax.sql.DataSource.class)).hasSize(1);
            assertThat(app.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                    .isFalse();
            assertThat(app.getBeansOfType(org.springframework.mail.javamail.JavaMailSender.class)).isEmpty();
            assertThat(app.getBeansOfType(org.springframework.amqp.rabbit.core.RabbitTemplate.class)).isEmpty();
            assertThat(app.getBeansOfType(org.springframework.data.redis.connection.RedisConnectionFactory.class))
                    .isEmpty();
            assertThat(app.getBean(CatalogCache.class).currentGeneration("products")).isEmpty();
            evidence.put("migrationHead", jdbc.queryForObject(
                    "select version from flyway_schema_history where success and version is not null "
                            + "order by installed_rank desc limit 1", String.class));
            evidence.put("postgresVersion", jdbc.queryForObject("show server_version", String.class));
            evidence.put("javaVersion", System.getProperty("java.version"));
            evidence.put("os", System.getProperty("os.name"));
            evidence.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            evidence.put("pool", Map.of("maximum", pool.getMaximumPoolSize(), "minimumIdle", pool.getMinimumIdle(),
                    "connectionTimeoutMs", pool.getConnectionTimeout(), "idleTimeoutMs", pool.getIdleTimeout(),
                    "maxLifetimeMs", pool.getMaxLifetime()));
            long initialOrderCount = count(jdbc, "orders");
            long initialPaymentCount = count(jdbc, "payments");
            long initialLineCount = count(jdbc, "order_items");
            seed(jdbc, new TransactionTemplate(app.getBean(PlatformTransactionManager.class)), ownedName);
            evidence.put("scratchTotalsBeforeWorkload", Map.of("products", count(jdbc, "products"),
                    "variants", count(jdbc, "variants"), "users", count(jdbc, "users"),
                    "customers", count(jdbc, "customers"), "orders", initialOrderCount,
                    "payments", initialPaymentCount, "orderLines", initialLineCount));
            long beforeBytes = databaseBytes(jdbc);
            assertThat(beforeBytes).isLessThan(MAX_FIXTURE_BYTES);
            evidence.put("fixture", Map.of("products", PRODUCTS, "variants", PRODUCTS, "customers", CLIENTS,
                    "initialStockPerVariant", INITIAL_STOCK, "quantityPerCheckout", QUANTITY,
                    "databaseBytesBeforeWorkload", beforeBytes));
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            String base = "http://127.0.0.1:" + port;
            JwtService jwt = app.getBean(JwtService.class);
            List<String> tokens = new ArrayList<>();
            for (int client = 0; client < CLIENTS; client++) {
                tokens.add(jwt.generateAccessToken(PREFIX + "user-" + client, "fixture" + client + "@example.invalid"));
            }
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build()) {
                assertThat(send(http, base + "/api/v1/cart/checkout", null, CHECKOUT_BODY).statusCode()).isEqualTo(401);
                assertThat(send(http, base + "/api/v1/cart/checkout", "invalid", CHECKOUT_BODY).statusCode()).isEqualTo(401);
                evidence.put("expectedAuthenticationRejections", 2);
                runBatch(http, base, tokens, WARMUP_PER_CLIENT, "warmup", false);
                for (int batch = 1; batch <= BATCHES; batch++) {
                    runBatch(http, base, tokens, SAMPLES_PER_CLIENT_PER_BATCH, "batch-" + batch, true);
                }
                Map<String, Object> aggregate = new LinkedHashMap<>();
                for (String operation : List.of("checkout", "catalogList", "catalogDetail")) {
                    List<Double> samples = new ArrayList<>();
                    for (Map<String, Object> batch : measurements) {
                        if (Boolean.TRUE.equals(batch.get("measured"))) {
                            Map<?, ?> summary = (Map<?, ?>) batch.get(operation);
                            for (Object value : (List<?>) summary.get("samplesMs")) {
                                samples.add((Double) value);
                            }
                        }
                    }
                    assertThat(samples).hasSize(60);
                    aggregate.put(operation, distribution(samples));
                }
                evidence.put("aggregateExcludingWarmup", aggregate);
                assertThat(orders).hasSize(CHECKOUTS);
                assertThat(errors.get()).isZero();
                assertThat(count(jdbc, "orders") - initialOrderCount).isEqualTo(CHECKOUTS);
                assertThat(count(jdbc, "payments") - initialPaymentCount).isEqualTo(CHECKOUTS);
                assertThat(count(jdbc, "order_items") - initialLineCount).isEqualTo(CHECKOUTS);
                assertThat(jdbc.queryForObject("select variant_stock from variants where variant_id = ?",
                        Integer.class, PREFIX + "variant-0")).isEqualTo(872);
                assertThat(jdbc.queryForObject("select count(*) from variants where variant_id like ? "
                        + "and variant_stock = 1000", Long.class, PREFIX + "%")).isEqualTo(15);
                assertThat(jdbc.queryForObject("select count(*) from cart_items ci join carts c on c.id = ci.cart_id "
                        + "join customers cu on cu.id = c.customer_id where cu.customer_id like ?",
                        Long.class, PREFIX + "%")).isZero();
                assertThat(jdbc.queryForObject("select count(*) from orders o join customers c on c.id = o.customer_id "
                        + "join payments p on p.order_id = o.id join order_items i on i.order_id = o.id "
                        + "where c.customer_id like ? and o.order_status = 'PENDING' and o.order_total = 20.00 "
                        + "and p.payment_method = 'COD' and p.payment_status = 'PENDING' and p.payment_amount = 20.00 "
                        + "and i.quantity = 2 and i.unit_price = 10.00", Long.class, PREFIX + "%"))
                        .isEqualTo(CHECKOUTS);
                for (int client = 0; client < CLIENTS; client++) {
                    assertThat(jdbc.queryForObject("select count(*) from orders o join customers c on c.id = o.customer_id "
                            + "where c.customer_id = ?", Long.class, PREFIX + "customer-" + client)).isEqualTo(32);
                }
                JsonNode finalDetail = successful(send(http, base + "/api/v1/products/" + PREFIX + "0", null, null));
                assertThat(finalDetail.at("/product/stock").asInt()).isEqualTo(872);
                assertThat(finalDetail.at("/variants/0/stock").asInt()).isEqualTo(872);
                JsonNode finalList = successful(send(http, base + catalogListPath(), null, null));
                assertThat(finalList.path("content").size()).isEqualTo(PRODUCTS);
                boolean found = false;
                for (JsonNode product : finalList.path("content")) {
                    if ((PREFIX + "product-0").equals(product.path("productId").asString())) {
                        assertThat(product.path("stock").asInt()).isEqualTo(872);
                        found = true;
                    }
                }
                assertThat(found).isTrue();
            }
            long afterBytes = databaseBytes(jdbc);
            assertThat(afterBytes).isLessThan(MAX_FIXTURE_BYTES);
            evidence.put("databaseBytesAfterWorkload", afterBytes);
            evidence.put("correctness", Map.of("orders", CHECKOUTS, "payments", CHECKOUTS,
                    "orderLines", CHECKOUTS, "stockRemaining", 872, "emptyOwnedCarts", CLIENTS));
            evidence.put("status", "correctness-passed; latency targets reported, not weakened or a hosted gate");
        } finally {
            try {
                closeOwnedResources(context, pool, database, evidence);
            } finally {
                evidence.put("httpRequests", requests.get());
                evidence.put("unexpectedHttpOrTransportErrors", errors.get());
                System.out.println("INVENTORY_PERF " + json.writeValueAsString(evidence));
            }
        }
    }

    @Test
    void springSetupFailureStillClosesStartedPoolAndDropsOnlyOwnedDatabase() throws Exception {
        ExternalPostgresDatabase database = ExternalPostgresDatabase.create();
        HikariDataSource pool = null;
        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        try {
            String name = ownedName(database);
            evidence.put("ownedDatabase", name);
            verifyIdentity(database, name);
            pool = ownedPool(database);
            HikariDataSource ownedPool = pool;
            assertThatThrownBy(() -> new SpringApplicationBuilder(Fixture.class)
                    .environment(isolatedEnvironment(database)).web(WebApplicationType.SERVLET)
                    .registerShutdownHook(false).initializers(application -> {
                        context.set(application);
                        application.getBeanFactory().registerSingleton("dataSource", ownedPool);
                        assertThat(new JdbcTemplate(ownedPool).queryForObject("select current_database()", String.class))
                                .isEqualTo(name);
                        assertThat(ownedPool.getHikariPoolMXBean().getTotalConnections()).isPositive();
                        throw new IllegalStateException("intentional fixture setup failure");
                    }).run()).isInstanceOf(IllegalStateException.class).hasMessage("intentional fixture setup failure");
        } finally {
            closeOwnedResources(context, pool, database, evidence);
            System.out.println("INVENTORY_PERF_FAILURE_CLEANUP " + json.writeValueAsString(evidence));
        }
        assertThat(evidence).containsEntry("poolClosed", true).containsEntry("ownedDatabaseAbsent", true);
    }

    private static HikariDataSource ownedPool(ExternalPostgresDatabase database) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(database.jdbcUrl());
        pool.setUsername(database.username());
        pool.setPassword(database.password());
        pool.setDriverClassName("org.postgresql.Driver");
        pool.setMaximumPoolSize(5);
        pool.setMinimumIdle(1);
        pool.setConnectionTimeout(10000);
        pool.setIdleTimeout(300000);
        pool.setMaxLifetime(600000);
        pool.setPoolName("inventory-performance");
        return pool;
    }

    private static void closeOwnedResources(AtomicReference<ConfigurableApplicationContext> context,
                                            HikariDataSource pool, ExternalPostgresDatabase database,
                                            Map<String, Object> evidence) throws Exception {
        try {
            if (context.get() != null) {
                context.get().close();
                assertThat(context.get().isActive()).isFalse();
                evidence.put("contextClosed", true);
            }
        } finally {
            try {
                if (pool != null) {
                    pool.close();
                    assertThat(pool.isClosed()).isTrue();
                    evidence.put("poolClosed", true);
                }
            } finally {
                if (database != null) {
                    database.drop();
                    verifyDropped(database);
                    evidence.put("ownedDatabaseAbsent", true);
                }
            }
        }
    }

    private void runBatch(HttpClient http, String base, List<String> tokens, int cycles,
                          String batch, boolean measured) throws Exception {
        Map<String, List<Double>> samples = Map.of("checkout", new ArrayList<>(),
                "catalogList", new ArrayList<>(), "catalogDetail", new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        long batchStart = System.nanoTime();
        try (var workers = Executors.newFixedThreadPool(CLIENTS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (String token : tokens) {
                futures.add(workers.submit(() -> {
                    start.await();
                    for (int cycle = 0; cycle < cycles; cycle++) {
                        JsonNode item = successful(send(http, base + "/api/v1/cart/items", token,
                                "{\"variantId\":\"" + PREFIX + "variant-0\",\"quantity\":2}"));
                        assertThat(item.path("quantity").asInt()).isEqualTo(2);
                        long began = System.nanoTime();
                        HttpResponse<String> checkout = send(http, base + "/api/v1/cart/checkout", token, CHECKOUT_BODY);
                        sample(samples, "checkout", began);
                        JsonNode order = successful(checkout);
                        assertThat(order.path("orderId").asString()).isNotBlank();
                        assertThat(orders.add(order.path("orderId").asString())).isTrue();
                        assertThat(order.path("total").decimalValue()).isEqualByComparingTo("20.00");
                        assertThat(order.path("items").size()).isEqualTo(1);
                        assertThat(order.at("/items/0/quantity").asInt()).isEqualTo(2);
                        assertThat(order.at("/items/0/variantId").asString()).isEqualTo(PREFIX + "variant-0");
                        assertThat(order.at("/payment/method").asString()).isEqualTo("COD");
                        assertThat(order.path("customerId").asString())
                                .isEqualTo(PREFIX + "customer-" + tokens.indexOf(token));
                        began = System.nanoTime();
                        HttpResponse<String> list = send(http, base + catalogListPath(), null, null);
                        sample(samples, "catalogList", began);
                        JsonNode products = successful(list);
                        assertThat(products.path("totalElements").asInt()).isEqualTo(PRODUCTS);
                        assertThat(products.path("content").size()).isEqualTo(PRODUCTS);
                        began = System.nanoTime();
                        HttpResponse<String> detail = send(http, base + "/api/v1/products/" + PREFIX + "0", null, null);
                        sample(samples, "catalogDetail", began);
                        JsonNode product = successful(detail);
                        assertThat(product.at("/product/productId").asString()).isEqualTo(PREFIX + "product-0");
                        assertThat(product.path("variants").size()).isEqualTo(1);
                        assertThat(product.at("/variants/0/variantId").asString()).isEqualTo(PREFIX + "variant-0");
                    }
                    return null;
                }));
            }
            start.countDown();
            try {
                for (Future<?> future : futures) {
                    future.get(300, TimeUnit.SECONDS);
                }
            } finally {
                workers.shutdownNow();
                assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch", batch);
        result.put("measured", measured);
        result.put("wallMs", (System.nanoTime() - batchStart) / 1_000_000.0);
        for (Map.Entry<String, List<Double>> entry : samples.entrySet()) {
            assertThat(entry.getValue()).hasSize(CLIENTS * cycles);
            result.put(entry.getKey(), distribution(entry.getValue()));
        }
        measurements.add(result);
    }

    private HttpResponse<String> send(HttpClient http, String url, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        requests.incrementAndGet();
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            boolean expectedProbe = (token == null || "invalid".equals(token)) && url.endsWith("/checkout");
            if (response.statusCode() != (expectedProbe ? 401 : 200)) {
                errors.incrementAndGet();
            }
            return response;
        } catch (Exception exception) {
            errors.incrementAndGet();
            // Do not include response bodies, addresses, JWTs, or datasource settings in diagnostics.
            throw new IllegalStateException("HTTP transport failed: " + exception.getClass().getSimpleName());
        }
    }

    private JsonNode successful(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("HTTP success is mandatory").isEqualTo(200);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("code").asInt()).isEqualTo(200);
        assertThat(envelope.path("data").isMissingNode()).isFalse();
        return envelope.path("data");
    }

    private static void sample(Map<String, List<Double>> samples, String operation, long start) {
        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
        synchronized (samples) {
            samples.get(operation).add(elapsed);
        }
    }

    private static Map<String, Object> distribution(List<Double> samples) {
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double mean = Arrays.stream(sorted).average().orElseThrow();
        double variance = Arrays.stream(sorted).map(value -> (value - mean) * (value - mean)).average().orElseThrow();
        return Map.of("count", sorted.length, "p50Ms", sorted[(int) Math.ceil(sorted.length * .50) - 1],
                "p95Ms", sorted[(int) Math.ceil(sorted.length * .95) - 1],
                "p99Ms", sorted[(int) Math.ceil(sorted.length * .99) - 1],
                "meanMs", mean, "populationVarianceMs2", variance, "minMs", sorted[0],
                "maxMs", sorted[sorted.length - 1], "samplesMs", samples);
    }

    @Test
    void distributionUsesNearestRankAndPopulationVariance() {
        Map<String, Object> result = distribution(List.of(4.0, 1.0, 3.0, 2.0));
        assertThat(result).containsEntry("count", 4).containsEntry("p50Ms", 2.0)
                .containsEntry("p95Ms", 4.0).containsEntry("p99Ms", 4.0)
                .containsEntry("meanMs", 2.5).containsEntry("populationVarianceMs2", 1.25);
    }

    private static String catalogListPath() {
        return "/api/v1/products?keyword=" + PREFIX + "&page=0&size=20";
    }

    private static void seed(JdbcTemplate jdbc, TransactionTemplate transactions, String ownedName) {
        transactions.executeWithoutResult(transaction -> {
            assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(ownedName);
            long role = jdbc.queryForObject("insert into roles (role_id, role_code, role_name) values (?, ?, ?) returning id",
                    Long.class, PREFIX + "role", PREFIX + "role", "Fixture");
            for (int client = 0; client < CLIENTS; client++) {
                long user = jdbc.queryForObject("insert into users (user_id, user_email, user_password_hash, role_id) "
                                + "values (?, ?, ?, ?) returning id", Long.class, PREFIX + "user-" + client,
                        "fixture" + client + "@example.invalid", "not-a-login-password", role);
                jdbc.update("insert into customers (customer_id, user_id, customer_name, customer_phone) values (?, ?, ?, ?)",
                        PREFIX + "customer-" + client, user, "Fixture", "0000000000");
            }
            for (int product = 0; product < PRODUCTS; product++) {
                long id = jdbc.queryForObject("insert into products (product_id, product_slug, product_name, product_description) "
                                + "values (?, ?, ?, ?) returning id", Long.class, PREFIX + "product-" + product,
                        PREFIX + product, PREFIX + product, "Bounded local fixture");
                jdbc.update("insert into variants (variant_id, variant_sku, variant_price, variant_stock, product_id) "
                                + "values (?, ?, 10.00, 1000, ?)", PREFIX + "variant-" + product, PREFIX + "sku-" + product, id);
            }
        });
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private static long databaseBytes(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select pg_database_size(current_database())", Long.class);
    }

    private static String ownedName(ExternalPostgresDatabase database) {
        String name = URI.create(database.jdbcUrl().substring("jdbc:".length())).getPath().substring(1);
        assertThat(name).matches("verify_event_foundation_[0-9a-f]{12}");
        return name;
    }

    private static void verifyIdentity(ExternalPostgresDatabase database, String ownedName) throws Exception {
        try (var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select current_database()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(ownedName);
        }
    }

    private static void verifyDropped(ExternalPostgresDatabase database) throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"), database.username(), database.password());
             var statement = connection.prepareStatement("select count(*) from pg_database where datname = ?")) {
            statement.setString(1, ownedName(database));
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private static StandardEnvironment isolatedEnvironment(ExternalPostgresDatabase database) {
        // No inherited config imports, profiles, JNDI, datasource/Flyway/SQL-init URLs, or provider settings.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.config.location", "optional:classpath:/inventory-performance-no-config.yml");
        properties.put("spring.main.banner-mode", "off");
        properties.put("server.port", 0);
        properties.put("server.address", "127.0.0.1");
        properties.put("logging.level.root", "WARN");
        properties.put("logging.level.org.hibernate", "ERROR");
        properties.put("spring.datasource.url", database.jdbcUrl());
        properties.put("spring.datasource.username", database.username());
        properties.put("spring.datasource.password", database.password());
        properties.put("spring.datasource.hikari.jdbc-url", database.jdbcUrl());
        properties.put("spring.flyway.enabled", false);
        properties.put("spring.flyway.url", database.jdbcUrl());
        properties.put("spring.flyway.user", database.username());
        properties.put("spring.flyway.password", database.password());
        properties.put("spring.sql.init.mode", "never");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.jpa.show-sql", false);
        properties.put("spring.jpa.open-in-view", true);
        properties.put("spring.jpa.properties.hibernate.default_batch_fetch_size", 50);
        properties.put("spring.jpa.properties.hibernate.cache.use_second_level_cache", false);
        properties.put("spring.jpa.properties.hibernate.cache.use_query_cache", false);
        properties.put("spring.jpa.properties.hibernate.generate_statistics", false);
        properties.put("app.notifications.delivery-enabled", false);
        properties.put("app.realtime.enabled", false);
        properties.put("app.realtime.retention-enabled", false);
        properties.put("spring.rabbitmq.dynamic", false);
        properties.put("spring.rabbitmq.listener.simple.auto-startup", false);
        properties.put("spring.rabbitmq.listener.direct.auto-startup", false);
        properties.put("spring.mail.test-connection", false);
        properties.put("jwt.secret", "inventory-fixture-only-key-not-used-by-production-0123456789");
        properties.put("jwt.access-token-expiration-ms", 900000);
        properties.put("jwt.refresh-token-expiration-ms", 604800000);
        environment.getPropertySources().addFirst(new MapPropertySource("inventory-fixture", properties));
        return environment;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
            "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
    @EntityScan("com.project.dacn.entity")
    @EnableJpaRepositories("com.project.dacn.repository")
    @ComponentScan("com.project.dacn.mapper")
    @Import({CartController.class, ProductController.class, CartService.class, ProductService.class,
            ReviewService.class, CustomerIdentity.class, SecurityConfig.class, JwtService.class,
            JwtAuthenticationFilter.class, CustomUserDetailsService.class, AuthenticationEntryPoint.class,
            CustomAccessDeniedHandler.class, GlobalExceptionHandler.class, ProductSortConverter.class,
            DatabaseClock.class, OrderEventOutbox.class, OrderEventCodec.class})
    private static class Fixture {
        // Curated imports omit all provider clients, mail services, delivery, Realtime, and @EnableScheduling.
        @Bean
        CatalogCache catalogCache(ObjectMapper mapper) {
            return new CatalogCache(null, mapper) {
                @Override
                public OptionalLong currentGeneration(String scope) {
                    return OptionalLong.empty();
                }
            };
        }
    }
}
