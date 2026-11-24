package com.project.dacn.support;

import org.flywaydb.core.Flyway;
import org.opentest4j.TestAbortedException;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates an isolated PostgreSQL database on the same server as the configured
 * test database, migrates it with the project migrations, and drops it again.
 *
 * <p>Configuration comes only from {@code TEST_POSTGRES_URL},
 * {@code TEST_POSTGRES_USERNAME}, and {@code TEST_POSTGRES_PASSWORD}. The values
 * are never logged.</p>
 */
public final class ExternalPostgresDatabase {

    private static final String URL_ENV = "TEST_POSTGRES_URL";
    private static final String USERNAME_ENV = "TEST_POSTGRES_USERNAME";
    private static final String PASSWORD_ENV = "TEST_POSTGRES_PASSWORD";
    private static final String DATABASE_PREFIX = "verify_event_foundation_";
    private static final String JDBC_PREFIX = "jdbc:postgresql://";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String databaseName;
    private final String adminJdbcUrl;
    private final String username;
    private final String password;
    private final String scratchJdbcUrl;

    private ExternalPostgresDatabase(String databaseName, String adminJdbcUrl,
                                     String username, String password) {
        this.databaseName = databaseName;
        this.adminJdbcUrl = adminJdbcUrl;
        this.username = username;
        this.password = password;
        this.scratchJdbcUrl = scratchJdbcUrl(adminJdbcUrl, databaseName);
    }

    public static ExternalPostgresDatabase create() {
        String url = System.getenv(URL_ENV);
        String user = System.getenv(USERNAME_ENV);
        String password = System.getenv(PASSWORD_ENV);

        List<String> missing = new ArrayList<>();
        if (isBlank(url)) {
            missing.add(URL_ENV);
        }
        if (isBlank(user)) {
            missing.add(USERNAME_ENV);
        }
        if (isBlank(password)) {
            missing.add(PASSWORD_ENV);
        }
        if (missing.size() == 3) {
            throw new TestAbortedException(
                    "TEST_POSTGRES_* is not configured; skipping external PostgreSQL tests");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Incomplete external PostgreSQL test configuration; missing: "
                            + String.join(", ", missing));
        }

        ExternalPostgresDatabase database = new ExternalPostgresDatabase(
                DATABASE_PREFIX + randomHex12(), url, user, password);
        database.createDatabase();
        return database;
    }

    public String jdbcUrl() {
        return scratchJdbcUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public void migrate() {
        try (Connection connection = DriverManager.getConnection(scratchJdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select current_database()")) {
            if (!rows.next() || !databaseName.equals(rows.getString(1))) {
                throw new IllegalStateException("Refusing to migrate unexpected PostgreSQL database");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to verify scratch PostgreSQL database identity");
        }
        // Flyway's default logger prints the JDBC URL; keep it out of test output.
        Flyway.configure()
                .dataSource(scratchJdbcUrl, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .loggers()
                .load()
                .migrate();
    }

    public void drop() {
        try (Connection connection = DriverManager.getConnection(adminJdbcUrl, username, password)) {
            if (!databaseExists(connection)) {
                return;
            }
            terminateBackends(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("drop database " + quoteIdentifier(databaseName) + " with (force)");
            }
        } catch (SQLException exception) {
            if ("3D000".equals(exception.getSQLState())) {
                return;
            }
            throw new IllegalStateException(
                    "Failed to drop scratch PostgreSQL database " + databaseName, exception);
        }
    }

    private void createDatabase() {
        try (Connection connection = DriverManager.getConnection(adminJdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("create database " + quoteIdentifier(databaseName));
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to create scratch PostgreSQL database " + databaseName, exception);
        }
    }

    private boolean databaseExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from pg_database where datname = ?")) {
            statement.setString(1, databaseName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void terminateBackends(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_terminate_backend(pid) from pg_stat_activity "
                        + "where datname = ? and pid <> pg_backend_pid()")) {
            statement.setString(1, databaseName);
            statement.execute();
        }
    }

    private static String scratchJdbcUrl(String adminJdbcUrl, String databaseName) {
        if (!adminJdbcUrl.startsWith(JDBC_PREFIX)) {
            throw new IllegalStateException(URL_ENV + " must start with " + JDBC_PREFIX);
        }
        int queryIndex = adminJdbcUrl.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? adminJdbcUrl.substring(0, queryIndex) : adminJdbcUrl;
        String query = queryIndex >= 0 ? adminJdbcUrl.substring(queryIndex) : "";
        int pathIndex = withoutQuery.indexOf('/', JDBC_PREFIX.length());
        String authority = pathIndex >= 0
                ? withoutQuery.substring(0, pathIndex)
                : withoutQuery;
        return authority + "/" + databaseName + query;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String randomHex12() {
        return String.format("%012x", RANDOM.nextLong() & 0x0000FFFFFFFFFFFFL);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
