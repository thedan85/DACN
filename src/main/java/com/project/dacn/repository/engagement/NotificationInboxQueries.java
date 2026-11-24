package com.project.dacn.repository.engagement;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.common.constant.NotificationType;
import com.project.dacn.dto.response.engagement.NotificationResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JDBC for the authorized inbox snapshot and owned mutations. The caller owns
 * one transaction and invokes at most one projection, one content query, and
 * one combined aggregate for a snapshot. Eligibility is resolved from current
 * role/permission/profile state inside that same snapshot, so a revocation also
 * hides historical COMPANY rows. Mutations reuse the same eligibility rules and
 * the same database-owned revision counter as in-app fan-out.
 */
@Repository
public class NotificationInboxQueries {

    public record Projection(Long userId, long revision, boolean companyEligible) {
    }

    public record Counts(long filteredTotal, long globalUnread) {
    }

    public record MutationState(long unreadCount, long revision) {
    }

    /** Empty sentinel keeps the status parameter non-null for every JDBC driver. */
    private static final String NO_FILTER = "";

    /** Single source of truth for current COMPANY audience authority. */
    private static final String COMPANY_ELIGIBLE_EXPRESSION = """
            (lower(r.role_code) IN ('admin', 'employee')
             AND EXISTS (
                 SELECT 1 FROM role_permissions rp
                 JOIN permissions p ON p.id = rp.permission_id
                 WHERE rp.role_id = u.role_id
                   AND lower(p.permission_code) = 'order:read'
             )
             AND CASE
                 WHEN lower(r.role_code) = 'admin' THEN NOT EXISTS (
                     SELECT 1 FROM admins a
                     WHERE a.user_id = u.id AND a.admin_status <> 'ACTIVE')
                 WHEN lower(r.role_code) = 'employee' THEN NOT EXISTS (
                     SELECT 1 FROM employees e
                     WHERE e.user_id = u.id AND e.employee_status <> 'ACTIVE')
                 ELSE TRUE
             END)""";

    private static final String PROJECTION_SQL =
            "SELECT u.id AS user_id, u.inbox_revision, "
                    + COMPANY_ELIGIBLE_EXPRESSION + " AS company_eligible "
                    + "FROM users u JOIN roles r ON r.id = u.role_id AND r.is_deleted = false "
                    + "WHERE u.user_id = :businessUserId AND u.user_status = 'ACTIVE'";

    /** Locks only the recipient user row before any notification row is read. */
    private static final String LOCK_PROJECTION_SQL =
            "SELECT u.id AS user_id, u.inbox_revision, "
                    + COMPANY_ELIGIBLE_EXPRESSION + " AS company_eligible "
                    + "FROM users u JOIN roles r ON r.id = u.role_id AND r.is_deleted = false "
                    + "WHERE u.user_id = :businessUserId AND u.user_status = 'ACTIVE' "
                    + "FOR UPDATE OF u";

    private static final String CONTENT_SQL = """
            SELECT n.notification_id, u.user_id AS owner_business_id,
                   n.notification_message, n.notification_type, n.notification_status,
                   n.created_at, n.updated_at, o.order_id AS order_business_id,
                   n.event_type, n.order_sequence, n.occurred_at
            FROM notifications n
            JOIN users u ON u.id = n.user_id
            LEFT JOIN orders o ON o.id = n.order_id
            WHERE n.user_id = :userId
              AND n.is_deleted = false
              AND (n.recipient_scope IS NULL
                   OR n.recipient_scope = 'CUSTOMER'
                   OR (:companyEligible AND n.recipient_scope = 'COMPANY'))
              AND (:filterStatus = '' OR n.notification_status = :filterStatus)
            ORDER BY n.occurred_at DESC, n.order_sequence DESC NULLS LAST, n.id DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String COUNTS_SQL = """
            SELECT count(*) FILTER (WHERE :filterStatus = ''
                                      OR n.notification_status = :filterStatus) AS filtered_total,
                   count(*) FILTER (WHERE n.notification_status = 'UNREAD') AS global_unread
            FROM notifications n
            WHERE n.user_id = :userId
              AND n.is_deleted = false
              AND (n.recipient_scope IS NULL
                   OR n.recipient_scope = 'CUSTOMER'
                   OR (:companyEligible AND n.recipient_scope = 'COMPANY'))
            """;

    /** Authoritative post-mutation unread count and revision, in one statement. */
    private static final String MUTATION_STATE_SQL = """
            SELECT (SELECT inbox_revision FROM users WHERE id = :userId) AS revision,
                   (SELECT count(*) FROM notifications n
                     WHERE n.user_id = :userId
                       AND n.is_deleted = false
                       AND n.notification_status = 'UNREAD'
                       AND (n.recipient_scope IS NULL
                            OR n.recipient_scope = 'CUSTOMER'
                            OR (:companyEligible AND n.recipient_scope = 'COMPANY'))) AS unread_count
            """;

    private static final String MARK_READ_SQL = """
            UPDATE notifications
               SET notification_status = 'READ',
                   updated_at = clock_timestamp()
             WHERE notification_id = :notificationId
               AND user_id = :userId
               AND is_deleted = false
               AND notification_status = 'UNREAD'
               AND (recipient_scope IS NULL
                    OR recipient_scope = 'CUSTOMER'
                    OR (:companyEligible AND recipient_scope = 'COMPANY'))
            """;

    private static final String SOFT_DELETE_SQL = """
            UPDATE notifications
               SET is_deleted = true,
                   updated_at = clock_timestamp()
             WHERE notification_id = :notificationId
               AND user_id = :userId
               AND is_deleted = false
               AND (recipient_scope IS NULL
                    OR recipient_scope = 'CUSTOMER'
                    OR (:companyEligible AND recipient_scope = 'COMPANY'))
            """;

    /** Task 3's explicit database-owned increment; exactly one row required. */
    private static final String INCREMENT_REVISION_SQL = """
            update users
               set inbox_revision = inbox_revision + 1
             where id = :userId
            returning inbox_revision
            """;

    private static final RowMapper<Projection> PROJECTION_MAPPER = (row, index) ->
            new Projection(row.getObject("user_id", Long.class),
                    row.getLong("inbox_revision"), row.getBoolean("company_eligible"));

    private static final RowMapper<Counts> COUNTS_MAPPER = (row, index) ->
            new Counts(row.getLong("filtered_total"), row.getLong("global_unread"));

    private static final RowMapper<MutationState> MUTATION_STATE_MAPPER = (row, index) ->
            new MutationState(row.getLong("unread_count"), row.getLong("revision"));

    private static final RowMapper<NotificationResponse> CONTENT_MAPPER = (row, index) -> {
        java.sql.Timestamp occurred = row.getTimestamp("occurred_at");
        return new NotificationResponse(
                row.getString("notification_id"),
                row.getString("owner_business_id"),
                row.getString("notification_message"),
                NotificationType.valueOf(row.getString("notification_type")),
                NotificationStatus.valueOf(row.getString("notification_status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getString("order_business_id"),
                row.getString("event_type"),
                row.getObject("order_sequence", Long.class),
                occurred == null ? null : occurred.toInstant());
    };

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationInboxQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Single projection query: active owner, current revision, company eligibility. */
    public Projection projection(String businessUserId) {
        return jdbc.query(PROJECTION_SQL,
                new MapSqlParameterSource("businessUserId", businessUserId),
                PROJECTION_MAPPER).stream().findFirst()
                .orElseGet(() -> new Projection(null, 0L, false));
    }

    /**
     * Projection plus a lock on the recipient user row. Call this before any
     * notification lookup so read/delete mutations serialize with fan-out.
     */
    public Projection lockProjection(String businessUserId) {
        return jdbc.query(LOCK_PROJECTION_SQL,
                new MapSqlParameterSource("businessUserId", businessUserId),
                PROJECTION_MAPPER).stream().findFirst()
                .orElseGet(() -> new Projection(null, 0L, false));
    }

    public List<NotificationResponse> content(long userId, boolean companyEligible,
                                              NotificationStatus filter, int page, int size) {
        return jdbc.query(CONTENT_SQL,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("companyEligible", companyEligible)
                        .addValue("filterStatus", filter == null ? NO_FILTER : filter.name())
                        .addValue("limit", size)
                        .addValue("offset", (long) page * size),
                CONTENT_MAPPER);
    }

    /** One combined aggregate: status-filtered total plus global visible unread. */
    public Counts counts(long userId, boolean companyEligible, NotificationStatus filter) {
        return jdbc.query(COUNTS_SQL,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("companyEligible", companyEligible)
                        .addValue("filterStatus", filter == null ? NO_FILTER : filter.name()),
                COUNTS_MAPPER).stream().findFirst()
                .orElseGet(() -> new Counts(0L, 0L));
    }

    /** Returns 1 when an eligible UNREAD owned row changed, 0 otherwise. */
    public int markRead(long userId, String notificationId, boolean companyEligible) {
        return jdbc.update(MARK_READ_SQL,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("notificationId", notificationId)
                        .addValue("companyEligible", companyEligible));
    }

    /** Returns 1 when an eligible visible owned row was soft-deleted, 0 otherwise. */
    public int softDelete(long userId, String notificationId, boolean companyEligible) {
        return jdbc.update(SOFT_DELETE_SQL,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("notificationId", notificationId)
                        .addValue("companyEligible", companyEligible));
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

    /** Authoritative visible unread count and revision after a mutation. */
    public MutationState mutationState(long userId, boolean companyEligible) {
        return jdbc.query(MUTATION_STATE_SQL,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("companyEligible", companyEligible),
                MUTATION_STATE_MAPPER).stream().findFirst()
                .orElseGet(() -> new MutationState(0L, 0L));
    }
}
