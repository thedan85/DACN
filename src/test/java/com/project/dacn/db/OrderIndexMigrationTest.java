package com.project.dacn.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OrderIndexMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void v26ReplacesSingleColumnIndexWithComposite() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(indexDefinition("idx_orders_customer_id_id"))
                .contains("ON public.orders USING btree")
                .endsWith("(customer_id, id DESC)");
        assertThat(indexDefinition("idx_orders_customer_id")).isNull();
    }

    private static String indexDefinition(String indexName) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?")) {
            statement.setString(1, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }
}
