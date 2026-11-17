package com.project.dacn.repository.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JDBC ownership of the SMTP delivery ledger. Every method runs inside a short
 * transaction owned by {@link com.project.dacn.service.engagement.EmailDeliveryService};
 * nothing here opens a socket or touches email content.
 */
@Repository
public class EmailDeliveryRepository {

    private static final String FIND_SQL = """
            select id, state, attempt_count, claim_token, claim_expires_at,
                   next_eligible_at, completed_at, failure_code
              from email_deliveries
             where event_id = :eventId and recipient_user_id = :userId
              for update
            """;

    private static final String INSERT_PENDING_SQL = """
            insert into email_deliveries (event_id, recipient_user_id, state, attempt_count)
            values (:eventId, :userId, 'PENDING', 0)
            on conflict (event_id, recipient_user_id) do nothing
            returning id
            """;

    private static final String INSERT_SKIPPED_SQL = """
            insert into email_deliveries
                (event_id, recipient_user_id, state, attempt_count, completed_at)
            values (:eventId, :userId, 'SKIPPED', 0, clock_timestamp())
            on conflict (event_id, recipient_user_id) do nothing
            returning id
            """;

    private static final String FRESH_ORDER_SQL = """
            select o.order_status, p.payment_status, o.payment_expires_at,
                   (o.payment_expires_at is not null
                    and o.stock_released_at is null
                    and o.order_status in ('PENDING', 'CONFIRMED', 'SHIPPING')
                   ) as deadline_active,
                   clock_timestamp() as db_now
              from orders o
              join payments p on p.order_id = o.id
             where o.order_id = :orderId
            """;

    private static final String RETRY_DUE_SQL = """
            select exists (
                select 1 from email_deliveries
                 where id = :id and (next_eligible_at is null
                                     or next_eligible_at <= clock_timestamp())
            )
            """;

    private static final String LEASE_EXPIRED_SQL = """
            select exists (
                select 1 from email_deliveries
                 where id = :id and state = 'IN_FLIGHT' and completed_at is null
                   and (claim_expires_at is null or claim_expires_at <= clock_timestamp())
            )
            """;

    private static final String CLAIM_SQL = """
            update email_deliveries
               set state = 'IN_FLIGHT',
                   attempt_count = attempt_count + 1,
                   claim_token = :token,
                   claim_expires_at = clock_timestamp()
                       + (cast(:leaseSeconds as text) || ' seconds')::interval,
                   next_eligible_at = null,
                   failure_code = null,
                   updated_at = clock_timestamp()
             where id = :id
               and state = 'PENDING'
               and (next_eligible_at is null or next_eligible_at <= clock_timestamp())
             returning attempt_count, claim_expires_at,
                       pg_current_xact_id()::text::bigint as commit_anchor
            """;

    private static final String QUARANTINE_EXPIRED_SQL = """
            update email_deliveries
               set state = 'UNKNOWN',
                   completed_at = clock_timestamp(),
                   failure_code = 'LEASE_EXPIRED_UNKNOWN',
                   claim_token = null,
                   claim_expires_at = null,
                   next_eligible_at = null,
                   updated_at = clock_timestamp()
             where id = :id
               and state = 'IN_FLIGHT' and completed_at is null
               and (claim_expires_at is null or claim_expires_at <= clock_timestamp())
            """;

    private static final String FENCE = """
                   and state = 'IN_FLIGHT' and completed_at is null
                   and id = :id
                   and claim_token = :token
                   and attempt_count = :attempt
                   and claim_expires_at = :leaseExpiry
            """;

    private static final String FINISH_TERMINAL_SQL = """
            update email_deliveries
               set state = :state,
                   completed_at = clock_timestamp(),
                   next_eligible_at = null,
                   failure_code = :failureCode,
                   claim_token = null,
                   claim_expires_at = null,
                   updated_at = clock_timestamp()
             where true
            """ + FENCE;

    private static final String FINISH_RETRY_SQL = """
            update email_deliveries
               set state = 'PENDING',
                   completed_at = null,
                   next_eligible_at = clock_timestamp()
                       + (cast(:delaySeconds as text) || ' seconds')::interval,
                   failure_code = :failureCode,
                   claim_token = null,
                   claim_expires_at = null,
                   updated_at = clock_timestamp()
             where true
            """ + FENCE;

    private final NamedParameterJdbcTemplate jdbc;

    public EmailDeliveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DeliveryRow(long id, String state, int attemptCount, UUID claimToken,
                              Instant claimExpiresAt, Instant nextEligibleAt,
                              Instant completedAt, String failureCode) {
    }

    public record FreshOrderState(OrderStatus orderStatus, PaymentStatus paymentStatus,
                                  Instant paymentExpiresAt, boolean deadlineActive,
                                  Instant databaseNow) {
    }

    public record ClaimedRow(int attemptCount, Instant claimExpiresAt, long commitAnchor) {
    }

    /** Locks an existing row so competing claims serialize; absent row means no lock. */
    public DeliveryRow findForUpdate(UUID eventId, long userId) {
        List<DeliveryRow> rows = jdbc.query(FIND_SQL, new MapSqlParameterSource()
                        .addValue("eventId", eventId).addValue("userId", userId),
                (result, index) -> new DeliveryRow(
                        result.getLong("id"), result.getString("state"),
                        result.getInt("attempt_count"), uuid(result.getString("claim_token")),
                        instant(result, "claim_expires_at"), instant(result, "next_eligible_at"),
                        instant(result, "completed_at"), result.getString("failure_code")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Returns null when database uniqueness already gave this row to another claim. */
    public Long insertPending(UUID eventId, long userId) {
        List<Long> ids = jdbc.query(INSERT_PENDING_SQL, new MapSqlParameterSource()
                        .addValue("eventId", eventId).addValue("userId", userId),
                (result, index) -> result.getLong("id"));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /** Returns null when another claim inserted the row first. */
    public Long insertSkipped(UUID eventId, long userId) {
        List<Long> ids = jdbc.query(INSERT_SKIPPED_SQL, new MapSqlParameterSource()
                        .addValue("eventId", eventId).addValue("userId", userId),
                (result, index) -> result.getLong("id"));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /** Fresh order/payment truth read under the claim transaction's clock_timestamp(). */
    public FreshOrderState freshOrderState(String orderId) {
        List<FreshOrderState> states = jdbc.query(FRESH_ORDER_SQL,
                new MapSqlParameterSource("orderId", orderId), (result, index) ->
                        new FreshOrderState(
                                OrderStatus.valueOf(result.getString("order_status")),
                                PaymentStatus.valueOf(result.getString("payment_status")),
                                instant(result, "payment_expires_at"),
                                result.getBoolean("deadline_active"),
                                instant(result, "db_now")));
        return states.isEmpty() ? null : states.getFirst();
    }

    public boolean isRetryDue(long id) {
        return jdbc.queryForObject(RETRY_DUE_SQL,
                new MapSqlParameterSource("id", id), Boolean.class);
    }

    public boolean isLeaseExpired(long id) {
        return jdbc.queryForObject(LEASE_EXPIRED_SQL,
                new MapSqlParameterSource("id", id), Boolean.class);
    }

    /** Returns the durable attempt, lease expiry and monotonic commit anchor. */
    public ClaimedRow claimPending(long id, UUID token, Duration lease) {
        List<ClaimedRow> rows = jdbc.query(CLAIM_SQL, new MapSqlParameterSource()
                        .addValue("id", id).addValue("token", token)
                        .addValue("leaseSeconds", lease.toSeconds()),
                (result, index) -> new ClaimedRow(result.getInt("attempt_count"),
                        instant(result, "claim_expires_at"), result.getLong("commit_anchor")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** An expired in-flight attempt is UNKNOWN evidence, never a fresh send. */
    public boolean quarantineExpired(long id) {
        return jdbc.update(QUARANTINE_EXPIRED_SQL,
                new MapSqlParameterSource("id", id)) == 1;
    }

    /** Terminal outcome; the token fence makes an old attempt a no-op. */
    public boolean finishTerminal(long id, UUID token, int attempt, Instant leaseExpiry,
                                  String state, String failureCode) {
        return jdbc.update(FINISH_TERMINAL_SQL, new MapSqlParameterSource()
                        .addValue("id", id).addValue("token", token)
                        .addValue("attempt", attempt).addValue("leaseExpiry", Timestamp.from(leaseExpiry))
                        .addValue("state", state).addValue("failureCode", failureCode)) == 1;
    }

    /** Retryable outcome; attempt count and bounded failure code stay durable. */
    public boolean finishRetry(long id, UUID token, int attempt, Instant leaseExpiry,
                               Duration delay, String failureCode) {
        return jdbc.update(FINISH_RETRY_SQL, new MapSqlParameterSource()
                        .addValue("id", id).addValue("token", token)
                        .addValue("attempt", attempt).addValue("leaseExpiry", Timestamp.from(leaseExpiry))
                        .addValue("delaySeconds", delay.toSeconds())
                        .addValue("failureCode", failureCode)) == 1;
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant instant(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
