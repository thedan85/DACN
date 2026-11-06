package com.project.dacn.common.time;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseClockTest {

    @Test
    void nowReadsClockTimestampAndConvertsItToAnInstant() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Instant expected = Instant.parse("2026-11-04T01:02:03.123456Z");
        when(jdbcTemplate.queryForObject("select clock_timestamp()", Timestamp.class))
                .thenReturn(Timestamp.from(expected));

        DatabaseClock clock = new DatabaseClock(jdbcTemplate);

        assertThat(clock.now()).isEqualTo(expected);
        verify(jdbcTemplate).queryForObject("select clock_timestamp()", Timestamp.class);
    }

    @Test
    void nullTimestampFailsLoudly() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("select clock_timestamp()", Timestamp.class))
                .thenReturn(null);

        DatabaseClock clock = new DatabaseClock(jdbcTemplate);

        assertThatThrownBy(clock::now)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null");
    }
}
