package com.project.dacn.integration.realtime;

import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringJUnitConfig(RealtimeAdmissionPostgresTest.Config.class)
class RealtimeAdmissionPostgresTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static ExternalPostgresDatabase database;
    private static String scratchDatabaseName;
    private static KeyPair keyPair;
    private static HikariDataSource pool;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RealtimeAdmissionRepository repository;
    @Autowired
    RealtimeAdmissionService service;
    @Autowired
    DatabaseClock clock;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    RealtimeProperties realtimeProperties;

    @AfterAll
    static void dropOnlyOwnedDatabase() throws Exception {
        if (pool != null) {
            pool.close();
        }
        if (database != null) {
            database.drop();
            try (Connection admin = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                    System.getenv("TEST_POSTGRES_USERNAME"), System.getenv("TEST_POSTGRES_PASSWORD"));
                 PreparedStatement statement = admin.prepareStatement("select 1 from pg_database where datname = ?")) {
                statement.setString(1, scratchDatabaseName);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("this invocation's scratch database was dropped").isFalse();
                }
            }
            System.out.println("Confirmed owned admission scratch cleanup: " + scratchDatabaseName);
        }
    }

    @BeforeEach
    void resetOwnedAdmissionState() {
        jdbc.update("delete from realtime_token_leases");
        jdbc.update("update app_realtime_control set enabled = true, remaining_message_budget = 1000 where id = 1");
    }

    @Test
    void actualProductStatusSchemaExcludesDraftInactiveDeletedAndUnknownWithoutRewritingIds() {
        String active = insertProduct("ACTIVE");
        String unusual = " PRD_'\"\\雪_" + UUID.randomUUID();
        insertProduct(unusual, "ACTIVE");
        List<String> requested = List.of(insertProduct("DRAFT"), active, insertProduct("INACTIVE"),
                insertProduct("DELETED"), "unknown-' OR true --", unusual, active);

        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.guest(), requested);

        assertThat(response.admittedProductIds()).containsExactly(active, unusual);
        assertThat(signed(response).get("stock_product_ids", List.class)).containsExactly(active, unusual);
        assertThat(repository.findActiveProductIds(List.of())).isEmpty();
        assertThat(repository.countUnretiredStockLeases(List.of(), clock.now())).isEmpty();
    }

    @Test
    void setBasedStockCountUsesRetirementNotExpiryAndCountsEachLeaseOnlyOncePerRequestedTopic() {
        Instant now = clock.now();
        seedLease(null, List.of("a", "b", "a"), false, now.minusSeconds(1), now.plusSeconds(29));
        seedLease(null, List.of("a", "b"), false, now.minusSeconds(30), now);
        seedLease(null, List.of("a"), false, now.minusSeconds(31), now.minusSeconds(1));
        seedLease(null, List.of("b", "unrequested"), false, now.plusSeconds(300), now.plusSeconds(330));

        assertThat(repository.countUnretiredStockLeases(List.of("a", "b", "absent"), now))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 1L, "b", 2L));
    }

    @Test
    void inboxCountUsesInternalUserIdInboxGrantAndStrictRetirementBoundary() {
        long firstUser = insertUser();
        long secondUser = insertUser();
        Instant now = clock.now();
        seedLease(firstUser, List.of(), true, now.minusSeconds(1), now.plusSeconds(29));
        seedLease(firstUser, List.of(), true, now.minusSeconds(30), now);
        seedLease(firstUser, List.of(), true, now.minusSeconds(31), now.minusSeconds(1));
        seedLease(firstUser, List.of(), false, now.plusSeconds(300), now.plusSeconds(330));
        seedLease(secondUser, List.of(), true, now.plusSeconds(300), now.plusSeconds(330));
        seedLease(secondUser, List.of(), true, now.plusSeconds(300), now.plusSeconds(330));

        assertThat(repository.countUnretiredInboxLeases(firstUser, now)).isEqualTo(1);
        assertThat(repository.countUnretiredInboxLeases(secondUser, now)).isEqualTo(2);
    }

    @Test
    void persistedLeaseMatchesVerifiedJwtAndResponseWithoutPersistingBearerOrSpendingSendBudget() {
        String product = insertProduct("ACTIVE");
        long user = insertUser();

        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.authenticated(user), List.of(product));
        Claims claims = signed(response);
        Map<String, Object> lease = jdbc.queryForMap("""
                select jti, user_id, admitted_product_ids::text as products, inbox_allowed, expires_at, retire_at
                  from realtime_token_leases where jti = ?
                """, UUID.fromString(claims.getId()));

        assertThat(lease.get("user_id")).isEqualTo(user);
        assertThat(JSON.readTree((String) lease.get("products"))).isEqualTo(JSON.valueToTree(List.of(product)));
        assertThat(lease.get("inbox_allowed")).isEqualTo(true);
        assertThat(((Timestamp) lease.get("expires_at")).toInstant()).isEqualTo(response.expiresAt());
        assertThat(response.expiresAt()).isEqualTo(claims.getExpiration().toInstant());
        assertThat(((Timestamp) lease.get("retire_at")).toInstant()).isEqualTo(response.expiresAt().plusSeconds(30));
        assertThat(response.inboxTopic()).isEqualTo("inbox:" + user);
        assertThat(jdbc.queryForObject("select row_to_json(l)::text from realtime_token_leases l where jti = ?",
                String.class, UUID.fromString(claims.getId()))).doesNotContain(response.accessToken());
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                 where table_schema = 'public' and table_name = 'realtime_token_leases'
                """, String.class)).containsExactlyInAnyOrder("jti", "user_id", "admitted_product_ids",
                "inbox_allowed", "expires_at", "retire_at");
        assertThat(jdbc.queryForObject("select remaining_message_budget from app_realtime_control where id = 1",
                Long.class)).isEqualTo(1000L);
    }

    @Test
    void twoConcurrentIssuersCannotBothTakeLastStockSlotIncludingExpiredUnretiredLeases() throws Exception {
        String product = insertProduct("ACTIVE");
        seedLeases(19, null, List.of(product), false);

        List<RealtimeCredentialResponse> responses = race(RealtimePrincipal.guest(), RealtimePrincipal.guest(), List.of(product));

        assertThat(responses).extracting(response -> response.admittedProductIds().size())
                .containsExactlyInAnyOrder(1, 0);
        assertThat(stockCount(product)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from realtime_token_leases", Long.class)).isEqualTo(21L);
        System.out.println("Stock admission race: two real lock waiters; grants 1/0; unretired topic count 20");
    }

    @Test
    void twoConcurrentIssuersCannotBothTakeLastOwnInboxSlot() throws Exception {
        long user = insertUser();
        seedLeases(19, user, List.of(), true);
        RealtimePrincipal principal = RealtimePrincipal.authenticated(user);

        List<RealtimeCredentialResponse> responses = race(principal, principal, List.of());

        assertThat(responses).extracting(RealtimeCredentialResponse::inboxTopic)
                .containsExactlyInAnyOrder("inbox:" + user, null);
        assertThat(inboxCount(user)).isEqualTo(20);
        System.out.println("Inbox admission race: two real lock waiters; grants 1/0; own-topic count 20");
    }

    @Test
    void saturatedAccountDoesNotConsumeAnotherAccountsLastInboxSlot() throws Exception {
        long saturatedUser = insertUser();
        long otherUser = insertUser();
        seedLeases(20, saturatedUser, List.of(), true);
        seedLeases(19, otherUser, List.of(), true);

        List<RealtimeCredentialResponse> responses = race(RealtimePrincipal.authenticated(saturatedUser),
                RealtimePrincipal.authenticated(otherUser), List.of());

        assertThat(responses.get(0).inboxTopic()).isNull();
        assertThat(responses.get(1).inboxTopic()).isEqualTo("inbox:" + otherUser);
        assertThat(inboxCount(saturatedUser)).isEqualTo(20);
        assertThat(inboxCount(otherUser)).isEqualTo(20);
        System.out.println("Per-account inbox race: saturated account denied; different account admitted; counts 20/20");
    }

    @Test
    void retirementDuringControlLockWaitUsesDatabaseTimeAfterLockAcquisition() throws Exception {
        String product = insertProduct("ACTIVE");
        seedLeases(20, null, List.of(product), false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection gate = lockedControl()) {
            Future<RealtimeCredentialResponse> result = executor.submit(
                    () -> service.issue(RealtimePrincipal.guest(), List.of(product)));
            awaitControlWaiters(1);
            try (PreparedStatement retire = gate.prepareStatement(
                    "update realtime_token_leases set retire_at = clock_timestamp()")) {
                assertThat(retire.executeUpdate()).isEqualTo(20);
            }
            gate.commit();

            assertThat(result.get(30, TimeUnit.SECONDS).admittedProductIds()).containsExactly(product);
            assertThat(stockCount(product)).isEqualTo(1);
            System.out.println("Post-lock database time: 20 leases retired during lock wait; fresh grant admitted");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void callerRollbackAlsoRollsBackTheLease() {
        String product = insertProduct("ACTIVE");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.issue(RealtimePrincipal.guest(), List.of(product));
            throw new IllegalStateException("caller rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("caller rollback");

        assertThat(jdbc.queryForObject("select count(*) from realtime_token_leases", Long.class)).isZero();
    }

    @Test
    void localOneHundredNinetyFiveProductInterestsAdmitTwentyAndLeaveTheRestInRestMode() {
        String product = insertProduct("ACTIVE");
        // Freeze the simulated instant so remote-pooler latency cannot retire early leases mid-loop.
        Instant frozenNow = clock.now();
        DatabaseClock frozenClock = new FrozenClock(jdbc, frozenNow);
        RealtimeAdmissionService frozenService = new RealtimeAdmissionService(
                realtimeProperties,
                new SupabaseRealtimeTokenService(realtimeProperties, frozenClock),
                frozenClock,
                repository);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        int admitted = 0;
        int restOnly = 0;
        for (int watcher = 0; watcher < 195; watcher++) {
            RealtimeCredentialResponse response = transactions.execute(
                    status -> frozenService.issue(RealtimePrincipal.guest(), List.of(product)));
            if (response.admittedProductIds().contains(product)) {
                admitted++;
            } else {
                restOnly++;
                assertThat(signed(response).get("stock_product_ids", List.class)).isEmpty();
            }
        }

        assertThat(admitted).isEqualTo(20);
        assertThat(restOnly).isEqualTo(175);
        assertThat(stockCountAt(product, frozenNow)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from realtime_token_leases", Long.class)).isEqualTo(195L);
        System.out.println("Local 195-interest simulation: 20 admitted; 175 empty signed grants / REST-only; no hosted sockets");
    }

    private List<RealtimeCredentialResponse> race(RealtimePrincipal first, RealtimePrincipal second,
                                                 List<String> products) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (Connection gate = lockedControl()) {
            Future<RealtimeCredentialResponse> firstResult = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return service.issue(first, products);
            });
            Future<RealtimeCredentialResponse> secondResult = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return service.issue(second, products);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            awaitControlWaiters(2);
            gate.commit();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Connection lockedControl() throws Exception {
        Connection connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(
                    "select enabled, remaining_message_budget from app_realtime_control where id = 1 for update")) {
                lock.executeQuery().close();
            }
            return connection;
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    private void awaitControlWaiters(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                     where datname = current_database() and wait_event_type = 'Lock'
                       and query like '%app_realtime_control%' and query like '%for update%'
                    """, Long.class);
            if (waiting == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(waiting).as("real PostgreSQL sessions blocked on the shared control row").isEqualTo(expected);
    }

    private void seedLeases(int count, Long user, List<String> products, boolean inbox) {
        Instant now = clock.now();
        for (int index = 0; index < count; index++) {
            seedLease(user, products, inbox, now.minusSeconds(1), now.plusSeconds(29));
        }
    }

    private void seedLease(Long user, List<String> products, boolean inbox, Instant expiresAt, Instant retireAt) {
        jdbc.update("""
                insert into realtime_token_leases
                    (jti, user_id, admitted_product_ids, inbox_allowed, expires_at, retire_at)
                values (?, ?, ?::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), user, JSON.writeValueAsString(products), inbox,
                Timestamp.from(expiresAt), Timestamp.from(retireAt));
    }

    private long stockCount(String product) {
        return stockCountAt(product, clock.now());
    }

    private long stockCountAt(String product, Instant at) {
        return jdbc.queryForObject("""
                select count(*) from realtime_token_leases
                 where retire_at > ? and admitted_product_ids @> ?::jsonb
                """, Long.class, Timestamp.from(at), JSON.writeValueAsString(List.of(product)));
    }

    private long inboxCount(long user) {
        return jdbc.queryForObject("""
                select count(*) from realtime_token_leases
                 where user_id = ? and inbox_allowed and retire_at > clock_timestamp()
                """, Long.class, user);
    }

    private String insertProduct(String status) {
        String id = "PRD_admission_" + UUID.randomUUID();
        insertProduct(id, status);
        return id;
    }

    private void insertProduct(String id, String status) {
        jdbc.update("""
                insert into products (product_id, product_name, product_description, product_slug, product_status)
                values (?, 'Admission product', 'Scratch fixture', ?, ?)
                """, id, "admission-" + UUID.randomUUID(), status);
    }

    private long insertUser() {
        String suffix = UUID.randomUUID().toString();
        long role = jdbc.queryForObject("""
                insert into roles (role_id, role_code, role_name) values (?, ?, 'Admission role') returning id
                """, Long.class, "role_" + suffix, "admission_" + suffix);
        return jdbc.queryForObject("""
                insert into users (user_id, user_email, user_password_hash, role_id, user_status)
                values (?, ?, 'scratch-only', ?, 'ACTIVE') returning id
                """, Long.class, "USR_" + suffix, suffix + "@example.invalid", role);
    }

    private Claims signed(RealtimeCredentialResponse response) {
        return Jwts.parser().verifyWith(keyPair.getPublic()).build()
                .parseSignedClaims(response.accessToken()).getPayload();
    }

    private static final class FrozenClock extends DatabaseClock {
        private final Instant frozen;

        private FrozenClock(JdbcTemplate jdbcTemplate, Instant frozen) {
            super(jdbcTemplate);
            this.frozen = frozen;
        }

        @Override
        public Instant now() {
            return frozen;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({RealtimeAdmissionService.class, RealtimeAdmissionRepository.class,
            SupabaseRealtimeTokenService.class, DatabaseClock.class})
    static class Config {

        @Bean(destroyMethod = "close")
        DataSource dataSource() {
            database = ExternalPostgresDatabase.create();
            scratchDatabaseName = URI.create(database.jdbcUrl().substring("jdbc:".length())).getPath().substring(1);
            database.migrate();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(database.jdbcUrl());
            config.setUsername(database.username());
            config.setPassword(database.password());
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(2);
            pool = new HikariDataSource(config);
            assertThat(new JdbcTemplate(pool).queryForObject("select current_database()", String.class))
                    .isEqualTo(scratchDatabaseName).startsWith("verify_event_foundation_");
            System.out.println("Verified owned admission scratch identity: " + scratchDatabaseName);
            return pool;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JSON;
        }

        @Bean
        RealtimeProperties realtimeProperties() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            keyPair = generator.generateKeyPair();
            RealtimeProperties properties = new RealtimeProperties();
            properties.setEnabled(true);
            properties.setSupabaseUrl("http://localhost:54321");
            properties.setSigningKeyId("scratch-only");
            properties.setPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            return properties;
        }
    }
}
