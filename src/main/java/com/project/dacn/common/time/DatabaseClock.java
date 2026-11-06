package com.project.dacn.common.time;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class DatabaseClock {

    private final JdbcTemplate jdbcTemplate;

    public Instant now() {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "select clock_timestamp()", Timestamp.class);
        if (timestamp == null) {
            throw new IllegalStateException("Database clock returned a null timestamp");
        }
        return timestamp.toInstant();
    }
}
