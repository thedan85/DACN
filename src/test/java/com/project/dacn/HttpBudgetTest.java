package com.project.dacn;

import com.project.dacn.security.JwtService;
import com.project.dacn.support.CountingDataSource;
import com.project.dacn.support.ExternalPostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 17 measured HTTP budget: real random-port server, real PostgreSQL, real
 * JWT/bearer auth, real notification endpoints. CountingDataSource proves the
 * inbox snapshot SQL budget on the real HTTP path, not a service-only call.
 */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.open-in-view=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "app.notifications.delivery-enabled=false",
        "app.realtime.enabled=false",
        "app.realtime.retention-enabled=false"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpBudgetTest {

    private static final int N = 120;
    private static final int REJECTIONS = 20;
    private static final String USER = "USR_t17_http";

    private static ExternalPostgresDatabase database;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (database == null) {
            database = ExternalPostgresDatabase.create();
            database.migrate();
        }
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
    }

    @AfterAll
    static void dropScratch() {
        if (database != null) {
            database.drop();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingConfig {
        @Bean
        @Primary
        CountingDataSource countingDataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(database.jdbcUrl());
            config.setUsername(database.username());
            config.setPassword(database.password());
            config.setMaximumPoolSize(10);
            config.setPoolName("t17-http-budget");
            return new CountingDataSource(new HikariDataSource(config));
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired CountingDataSource counting;

    private HttpClient client;
    private String base;
    private String bearer;
    private final List<RequestSample> list = new ArrayList<>();
    private final List<RequestSample> unread = new ArrayList<>();
    private final List<RequestSample> rejected = new ArrayList<>();

    @BeforeAll
    void seedAndToken() throws Exception {
        jdbc.update("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select ?, 't17_http@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'customer'", USER);
        for (int i = 0; i < 5; i++) {
            jdbc.update("insert into notifications (notification_id, user_id, notification_message, "
                            + "notification_type, notification_status, occurred_at) values (?, "
                            + "(select id from users where user_id = ?), ?, 'ORDER', 'UNREAD', clock_timestamp())",
                    "NOTI_t17_http_" + i, USER, "fixture " + i);
        }
        bearer = "Bearer " + jwtService.generateAccessToken(USER, "t17_http@example.invalid");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        base = "http://127.0.0.1:" + port;
        for (int i = 0; i < 20; i++) {
            measured("WARMUP", "/api/v1/notifications?page=0&size=20", null, null); // warm up JIT/pool/security cache
        }
    }

    @Test
    void listAndUnreadCountStayWithinHttpAndSqlBudgets() throws Exception {
        for (int i = 0; i < N; i++) {
            list.add(measured("LIST", "/api/v1/notifications?page=0&size=20", null, null));
            unread.add(measured("UNREAD", "/api/v1/notifications/unread-count", null, null));
        }
        for (int i = 0; i < REJECTIONS; i++) {
            rejected.add(measured("REJECT", "/api/v1/notifications?page=0&size=101", null, null));
        }

        List<Double> listMs = latencies(list);
        List<Double> unreadMs = latencies(unread);
        double listP50 = percentile(listMs, 0.50);
        double listP95 = percentile(listMs, 0.95);
        double listP99 = percentile(listMs, 0.99);
        double unreadP50 = percentile(unreadMs, 0.50);
        double unreadP95 = percentile(unreadMs, 0.95);
        double unreadP99 = percentile(unreadMs, 0.99);

        long unexpected = list.stream().filter(s -> s.unexpected).count()
                + unread.stream().filter(s -> s.unexpected).count();
        long businessRejections = rejected.stream().filter(s -> s.status >= 400 && s.status < 500).count()
                + list.stream().filter(s -> s.status >= 400 && s.status < 500).count()
                + unread.stream().filter(s -> s.status >= 400 && s.status < 500).count();
        double unexpectedRate = (double) unexpected / (N * 2);
        int maxInboxSql = list.stream().mapToInt(s -> s.inboxSql).max().orElseThrow();

        Path evidence = writeJson(list, unread, rejected, listP50, listP95, listP99,
                unreadP50, unreadP95, unreadP99, unexpected, businessRejections, maxInboxSql);
        System.out.printf("TASK17_HTTP_BUDGET listN=%d listP50=%.1fms listP95=%.1fms listP99=%.1fms "
                        + "unreadN=%d unreadP50=%.1fms unreadP95=%.1fms unreadP99=%.1fms "
                        + "unexpected5xxOrTransport=%d rate=%.4f businessRejections=%d maxInboxSql=%d%n",
                N, listP50, listP95, listP99, N, unreadP50, unreadP95, unreadP99,
                unexpected, unexpectedRate, businessRejections, maxInboxSql);
        System.out.println("TASK17_HTTP_EVIDENCE_FILE=" + evidence);

        assertThat(listP95).as("notification list p95, ms").isLessThanOrEqualTo(500.0);
        assertThat(unreadP95).as("unread count p95, ms").isLessThanOrEqualTo(500.0);
        assertThat(unexpectedRate).as("unexpected 5xx/transport rate").isLessThan(0.01);
        assertThat(businessRejections).as("expected business rejections observed").isEqualTo(REJECTIONS);
        assertThat(maxInboxSql).as("inbox snapshot SQL statements per list request").isLessThanOrEqualTo(3);
    }

    private RequestSample measured(String kind, String path, String method, String body) throws Exception {
        counting.reset();
        long t0 = System.nanoTime();
        int status;
        boolean transportFailure = false;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", bearer)
                    .timeout(Duration.ofSeconds(10));
            if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                        StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpResponse<Void> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());
            status = response.statusCode();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            status = -1;
            transportFailure = true;
        }
        long elapsed = System.nanoTime() - t0;
        List<String> statements = counting.statements();
        int inboxSql = (int) statements.stream().filter(this::isInboxSnapshotSql).count();
        return new RequestSample(kind, status, elapsed / 1e6, inboxSql, statements.size(),
                transportFailure || status >= 500);
    }

    /** Count only the three known snapshot signatures; auth/JPA reads are reported separately. */
    private boolean isInboxSnapshotSql(String sql) {
        return sql.contains("u.inbox_revision") && sql.contains("company_eligible")
                || sql.contains("global_unread")
                || sql.contains("FROM notifications n") && sql.contains("OFFSET");
    }

    private static List<Double> latencies(List<RequestSample> samples) {
        return samples.stream().filter(s -> !s.unexpected)
                .mapToDouble(s -> s.ms).boxed().toList();
    }

    private static double percentile(List<Double> values, double fraction) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int rank = (int) Math.ceil(fraction * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private Path writeJson(List<RequestSample> list, List<RequestSample> unread,
                           List<RequestSample> rejected, double lp50, double lp95, double lp99,
                           double up50, double up95, double up99, long unexpected,
                           long businessRejections, int maxInboxSql) throws IOException {
        String dir = System.getenv("TASK17_EVIDENCE_DIR");
        Path path = (dir == null || dir.isBlank())
                ? Paths.get("target", "task-17-evidence", "http-budget-samples.json")
                : Paths.get(dir, "http-budget-samples.json");
        Files.createDirectories(path.getParent());
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"server\": \"real RANDOM_PORT Spring server, real PostgreSQL, real bearer token\",\n");
        json.append("  \"sqlMethod\": \"CountingDataSource; count statements matching inbox snapshot SQL "
                + "signatures (projection/content/counts); all statements per request also recorded\",\n");
        json.append("  \"summary\": {\"listN\":").append(N)
                .append(",\"listP50Ms\":").append(lp50)
                .append(",\"listP95Ms\":").append(lp95)
                .append(",\"listP99Ms\":").append(lp99)
                .append(",\"unreadN\":").append(N)
                .append(",\"unreadP50Ms\":").append(up50)
                .append(",\"unreadP95Ms\":").append(up95)
                .append(",\"unreadP99Ms\":").append(up99)
                .append(",\"unexpected5xxOrTransport\":").append(unexpected)
                .append(",\"expectedBusinessRejections\":").append(businessRejections)
                .append(",\"maxInboxSqlPerListRequest\":").append(maxInboxSql)
                .append(",\"targets\":{\"p95Ms\":500,\"sql\":3,\"unexpectedRate\":0.01}},\n");
        json.append("  \"samples\": [\n");
        String[] separator = {""};
        writeSamples(json, list, "LIST", separator);
        writeSamples(json, unread, "UNREAD", separator);
        writeSamples(json, rejected, "REJECT", separator);
        json.append("\n  ]\n}\n");
        Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
        return path;
    }

    private static void writeSamples(StringBuilder json, List<RequestSample> samples, String kind,
                                     String[] separator) {
        for (RequestSample s : samples) {
            json.append(separator[0]);
            separator[0] = ",\n";
            json.append("    {\"kind\":\"").append(kind).append("\",\"status\":").append(s.status)
                    .append(",\"ms\":").append(s.ms)
                    .append(",\"inboxSql\":").append(s.inboxSql)
                    .append(",\"allStatements\":").append(s.allStatements)
                    .append(",\"unexpected\":").append(s.unexpected).append("}");
        }
    }

    private record RequestSample(String kind, int status, double ms, int inboxSql,
                                 int allStatements, boolean unexpected) { }
}
