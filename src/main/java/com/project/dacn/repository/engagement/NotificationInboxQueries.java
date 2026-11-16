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
 * Read-only JDBC for the authorized inbox snapshot. The caller owns one
 * repeatable-read transaction and invokes at most one projection, one content
 * query, and one combined aggregate in it. Eligibility is resolved from the
 * current role/permission/profile state inside that same snapshot, so a
 * revocation also hides historical COMPANY rows.
 */
@Repository
public class NotificationInboxQueries {

    public record Projection(Long userId, long revision, boolean companyEligible) {
    }

    public record Counts(long filteredTotal, long globalUnread) {
    }

    /** Empty sentinel keeps the status parameter non-null for every JDBC driver. */
    private static final String NO_FILTER = "";

    private static final String PROJECTION_SQL = """
            SELECT u.id AS user_id,
                   u.inbox_revision,
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
                    END) AS company_eligible
            FROM users u
            JOIN roles r ON r.id = u.role_id AND r.is_deleted = false
            WHERE u.user_id = :businessUserId
              AND u.user_status = 'ACTIVE'
            """;

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

    private static final RowMapper<Projection> PROJECTION_MAPPER = (row, index) ->
            new Projection(row.getObject("user_id", Long.class),
                    row.getLong("inbox_revision"), row.getBoolean("company_eligible"));

    private static final RowMapper<Counts> COUNTS_MAPPER = (row, index) ->
            new Counts(row.getLong("filtered_total"), row.getLong("global_unread"));

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
}
