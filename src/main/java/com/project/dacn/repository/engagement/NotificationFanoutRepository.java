package com.project.dacn.repository.engagement;

import com.project.dacn.entity.engagement.NotificationRecipientScope;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.util.stream.Collectors.joining;

/**
 * JDBC ownership of the in-app fan-out evidence and side effects. Every method
 * runs inside the single short transaction owned by
 * {@link com.project.dacn.service.engagement.NotificationFanoutService}; the
 * caller owns the transaction and the rollback.
 */
@Repository
public class NotificationFanoutRepository {

    private static final String CLAIM_RECEIPT_SQL = """
            insert into notification_event_receipts (event_id, recipient_user_ids, claimed_at)
            values (:eventId, '[]'::jsonb, clock_timestamp())
            on conflict (event_id) do nothing
            returning id
            """;

    private static final String RECEIPT_STATE_SQL = """
            select completed_at, failure_code
              from notification_event_receipts
             where event_id = :eventId
            """;

    private static final String QUARANTINE_NEW_SQL = """
            insert into notification_event_receipts
                (event_id, recipient_user_ids, claimed_at, completed_at, failure_code)
            values (:eventId, '[]'::jsonb, clock_timestamp(), clock_timestamp(), :failureCode)
            on conflict (event_id) do nothing
            """;

    private static final String QUARANTINE_SQL = """
            update notification_event_receipts
               set failure_code = :failureCode,
                   completed_at = clock_timestamp(),
                   updated_at = clock_timestamp()
             where event_id = :eventId
               and completed_at is null
            """;

    private static final String ORDER_SQL = "select id from orders where order_id = :orderId";

    private static final String LOCK_USER_SQL =
            "select id from users where id = :userId for update";

    private static final String INSERT_NOTIFICATION_SQL = """
            insert into notifications
                (notification_id, user_id, notification_message, notification_type,
                 notification_status, source_event_id, order_id, event_type,
                 order_sequence, occurred_at, recipient_scope)
            values
                (:notificationId, :userId, :message, 'ORDER', 'UNREAD',
                 :sourceEventId, :orderId, :eventType, :orderSequence, :occurredAt, :scope)
            on conflict (source_event_id, user_id) do nothing
            returning id
            """;

    private static final String INCREMENT_REVISION_SQL = """
            update users
               set inbox_revision = inbox_revision + 1
             where id = :userId
            returning inbox_revision
            """;

    private static final String COMPLETE_RECEIPT_SQL = """
            update notification_event_receipts
               set recipient_user_ids = cast(:recipientIds as jsonb),
                   completed_at = clock_timestamp(),
                   updated_at = clock_timestamp(),
                   failure_code = null
             where event_id = :eventId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationFanoutRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum ReceiptState { ABSENT, COMPLETED, QUARANTINED, CLAIMED_INCOMPLETE }

    public record NotificationInsert(
            long userId,
            String notificationId,
            String message,
            UUID sourceEventId,
            long orderSurrogateId,
            String eventType,
            long orderSequence,
            Instant occurredAt,
            NotificationRecipientScope scope) {
    }

    /** Returns the receipt id, or null when database uniqueness lost the claim. */
    public Long claimReceipt(UUID eventId) {
        List<Long> ids = jdbc.query(CLAIM_RECEIPT_SQL,
                new MapSqlParameterSource("eventId", eventId),
                (row, index) -> row.getLong("id"));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public ReceiptState receiptState(UUID eventId) {
        List<ReceiptState> states = jdbc.query(RECEIPT_STATE_SQL,
                new MapSqlParameterSource("eventId", eventId),
                (row, index) -> {
                    if (row.getString("failure_code") != null) {
                        return ReceiptState.QUARANTINED;
                    }
                    if (row.getTimestamp("completed_at") != null) {
                        return ReceiptState.COMPLETED;
                    }
                    return ReceiptState.CLAIMED_INCOMPLETE;
                });
        return states.isEmpty() ? ReceiptState.ABSENT : states.getFirst();
    }

    public void quarantineNew(UUID eventId, String failureCode) {
        jdbc.update(QUARANTINE_NEW_SQL, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("failureCode", failureCode));
    }

    public void quarantine(UUID eventId, String failureCode) {
        jdbc.update(QUARANTINE_SQL, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("failureCode", failureCode));
    }

    public Long resolveOrderSurrogateId(String orderId) {
        List<Long> ids = jdbc.query(ORDER_SQL,
                new MapSqlParameterSource("orderId", orderId),
                (row, index) -> row.getLong("id"));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /** Locks each surviving recipient row in ascending internal user ID order. */
    public List<Long> lockRecipientsAscending(List<Long> userIds) {
        List<Long> sorted = userIds.stream().distinct().sorted().toList();
        List<Long> locked = new ArrayList<>();
        for (Long userId : sorted) {
            List<Long> rows = jdbc.query(LOCK_USER_SQL,
                    new MapSqlParameterSource("userId", userId),
                    (row, index) -> row.getLong("id"));
            if (!rows.isEmpty()) {
                locked.add(userId);
            }
        }
        return locked;
    }

    /** Returns true only when a new (event, user) notification row was inserted. */
    public boolean insertNotificationIfAbsent(NotificationInsert insert) {
        List<Long> ids = jdbc.query(INSERT_NOTIFICATION_SQL, new MapSqlParameterSource()
                        .addValue("notificationId", insert.notificationId())
                        .addValue("userId", insert.userId())
                        .addValue("message", insert.message())
                        .addValue("sourceEventId", insert.sourceEventId())
                        .addValue("orderId", insert.orderSurrogateId())
                        .addValue("eventType", insert.eventType())
                        .addValue("orderSequence", insert.orderSequence())
                        .addValue("occurredAt", Timestamp.from(insert.occurredAt()))
                        .addValue("scope", insert.scope().name()),
                (row, index) -> row.getLong("id"));
        return !ids.isEmpty();
    }

    /** Database-owned increment; requires exactly one updated row. */
    public long incrementInboxRevision(long userId) {
        List<Long> revisions = jdbc.query(INCREMENT_REVISION_SQL,
                new MapSqlParameterSource("userId", userId),
                (row, index) -> row.getLong("inbox_revision"));
        if (revisions.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one inbox_revision row for user " + userId);
        }
        return revisions.getFirst();
    }

    public void completeReceipt(UUID eventId, List<Long> frozenUserIds) {
        String json = "[" + frozenUserIds.stream().map(String::valueOf).collect(joining(",")) + "]";
        jdbc.update(COMPLETE_RECEIPT_SQL, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("recipientIds", json));
    }
}
