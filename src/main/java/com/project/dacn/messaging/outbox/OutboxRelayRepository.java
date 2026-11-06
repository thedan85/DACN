package com.project.dacn.messaging.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OutboxRelayRepository {

    private static final int MIN_CLAIM_LIMIT = 1;
    private static final int MAX_CLAIM_LIMIT = 20;
    private static final Duration MAX_CLAIM_LEASE = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedOrderEvent> claimDue(int limit, Instant now, Duration lease,
                                            String owner, UUID claimToken) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        if (lease.compareTo(MAX_CLAIM_LEASE) > 0) {
            throw new IllegalArgumentException("lease must not exceed 5 minutes");
        }

        int claimLimit = Math.max(MIN_CLAIM_LIMIT, Math.min(MAX_CLAIM_LIMIT, limit));
        Timestamp nowTimestamp = Timestamp.from(now);
        Timestamp leaseExpiresAt = Timestamp.from(now.plus(lease));

        return jdbcTemplate.query("""
                with due as (
                    select id
                      from event_outbox
                     where published_at is null
                       and quarantined_at is null
                       and available_at <= ?
                       and (lease_expires_at is null or lease_expires_at < ?)
                     order by available_at, id
                     limit ?
                     for update skip locked
                ), claimed as (
                    update event_outbox o
                       set lease_owner = ?,
                           lease_token = ?,
                           lease_expires_at = ?,
                           attempt_count = o.attempt_count + 1
                      from due
                     where o.id = due.id
                    returning o.id, o.event_id, o.order_id, o.order_sequence,
                              o.event_type, o.payload::text, o.attempt_count,
                              o.lease_token, o.available_at
                )
                select id, event_id, order_id, order_sequence, event_type,
                       payload, attempt_count, lease_token
                  from claimed
                 order by available_at, id
                """,
                (resultSet, rowNumber) -> new ClaimedOrderEvent(
                        resultSet.getLong("id"),
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getLong("order_id"),
                        resultSet.getLong("order_sequence"),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getObject("lease_token", UUID.class)),
                nowTimestamp, nowTimestamp, claimLimit, owner, claimToken, leaseExpiresAt);
    }

    @Transactional
    public boolean markPublished(long id, UUID claimToken, Instant publishedAt) {
        return jdbcTemplate.update("""
                update event_outbox
                   set published_at = ?,
                       lease_owner = null,
                       lease_token = null,
                       lease_expires_at = null
                 where id = ?
                   and lease_token = ?
                   and published_at is null
                   and quarantined_at is null
                """, Timestamp.from(publishedAt), id, claimToken) == 1;
    }

    @Transactional
    public boolean releaseForRetry(long id, UUID claimToken, Instant availableAt, String errorCode) {
        return jdbcTemplate.update("""
                update event_outbox
                   set available_at = ?,
                       last_error_code = ?,
                       lease_owner = null,
                       lease_token = null,
                       lease_expires_at = null
                 where id = ?
                   and lease_token = ?
                   and published_at is null
                   and quarantined_at is null
                """, Timestamp.from(availableAt), errorCode, id, claimToken) == 1;
    }

    @Transactional
    public boolean quarantine(long id, UUID claimToken, Instant quarantinedAt, String errorCode) {
        return jdbcTemplate.update("""
                update event_outbox
                   set quarantined_at = ?,
                       last_error_code = ?,
                       lease_owner = null,
                       lease_token = null,
                       lease_expires_at = null
                 where id = ?
                   and lease_token = ?
                   and published_at is null
                   and quarantined_at is null
                """, Timestamp.from(quarantinedAt), errorCode, id, claimToken) == 1;
    }
}
