package com.project.dacn.integration.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.realtime.retention-enabled", havingValue = "true",
        matchIfMissing = true)
public class RealtimeRetentionPruner {

    static final String DELETE_RETIRED_LEASES = """
            delete from realtime_token_leases
             where jti in (select jti from realtime_token_leases
                            where retire_at <= clock_timestamp()
                            order by retire_at
                            limit ? for update skip locked)
            """;
    static final String DELETE_STALE_HINTS = """
            delete from realtime_signal_outbox
             where id in (select id from realtime_signal_outbox
                           where created_at <= clock_timestamp() - interval '30 seconds'
                           order by id
                           limit ? for update skip locked)
            """;
    static final String DELETE_OLD_ACCOUNTING = """
            delete from realtime_send_accounting
             where id in (select id from realtime_send_accounting
                           where sent_at <= clock_timestamp() - interval '1 hour'
                           order by id
                           limit ? for update skip locked)
            """;

    @Value("${app.realtime.retention-batch-size:1000}")
    private int batchSize = 1000;

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(fixedDelayString = "${app.realtime.retention-interval-ms:60000}")
    public void prune() {
        int size = Math.max(1, Math.min(batchSize, 10_000));
        deleteBatch(DELETE_RETIRED_LEASES, size);
        deleteBatch(DELETE_STALE_HINTS, size);
        deleteBatch(DELETE_OLD_ACCOUNTING, size);
    }

    private void deleteBatch(String sql, int size) {
        try {
            jdbcTemplate.update(sql, statement -> {
                statement.setInt(1, size);
                // A table lock must not monopolize the shared scheduler thread.
                statement.setQueryTimeout(1);
            });
        } catch (DataAccessException exception) {
            log.warn("realtime retention failed code=RETENTION_BATCH_FAILED");
        }
    }
}
