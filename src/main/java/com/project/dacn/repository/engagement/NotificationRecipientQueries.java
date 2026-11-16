package com.project.dacn.repository.engagement;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Server-owned recipient resolution for order notifications. Audience comes
 * only from active users and their current role/permission/profile; no target
 * user ID or address is ever accepted from an event or a browser claim.
 */
@Repository
public class NotificationRecipientQueries {

    /** Server-owned customer projection; ids come from the users table only. */
    public record CustomerRecipient(
            long userId, String businessUserId, String email, String roleCode) {
    }

    /** Server-owned company projection; deliberately carries no email. */
    public record CompanyRecipient(
            long userId, String businessUserId, String roleCode) {
    }

    private static final String CUSTOMER_SQL = """
            SELECT u.id, u.user_id, u.user_email, lower(r.role_code) AS role_code
            FROM users u
            JOIN roles r ON r.id = u.role_id AND r.is_deleted = false
            WHERE u.user_id = :businessUserId AND u.user_status = 'ACTIVE'
            """;

    private static final String COMPANY_SQL = """
            SELECT DISTINCT u.id, u.user_id, lower(r.role_code) AS role_code
            FROM users u
            JOIN roles r ON r.id = u.role_id AND r.is_deleted = false
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
                AND lower(p.permission_code) = 'order:read'
            LEFT JOIN admins a ON a.user_id = u.id
            LEFT JOIN employees e ON e.user_id = u.id
            WHERE u.user_status = 'ACTIVE'
              AND lower(r.role_code) IN ('admin', 'employee')
              AND (
                (lower(r.role_code) = 'admin'
                    AND (a.id IS NULL OR a.admin_status = 'ACTIVE'))
                OR (lower(r.role_code) = 'employee'
                    AND (e.id IS NULL OR e.employee_status = 'ACTIVE'))
              )
            ORDER BY u.id ASC
            """;

    private static final RowMapper<CustomerRecipient> CUSTOMER_MAPPER = (row, index) ->
            new CustomerRecipient(row.getLong("id"), row.getString("user_id"),
                    row.getString("user_email"), row.getString("role_code"));

    private static final RowMapper<CompanyRecipient> COMPANY_MAPPER = (row, index) ->
            new CompanyRecipient(row.getLong("id"), row.getString("user_id"),
                    row.getString("role_code"));

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public NotificationRecipientQueries(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CustomerRecipient> customerRecipient(String customerBusinessUserId) {
        return jdbcTemplate.query(CUSTOMER_SQL,
                new MapSqlParameterSource("businessUserId", customerBusinessUserId),
                CUSTOMER_MAPPER).stream().findFirst();
    }

    public List<CompanyRecipient> companyRecipients() {
        return jdbcTemplate.query(COMPANY_SQL, COMPANY_MAPPER);
    }
}
