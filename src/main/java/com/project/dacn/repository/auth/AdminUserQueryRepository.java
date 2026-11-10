package com.project.dacn.repository.auth;

import com.project.dacn.common.constant.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native read model for the admin user list. The profile columns are selected
 * with CASE on the user's current role so a stale profile from an old role can
 * never shadow the current one.
 *
 * <p>Optional filters are appended conditionally instead of being expressed as
 * {@code (:param IS NULL OR ...)}. That form makes PostgreSQL unable to infer a
 * type for the bare parameter and can defeat the useful index plan. The keyword
 * pattern is always bound, and its LIKE wildcards are escaped so a user typing
 * {@code %} or {@code _} searches for those literal characters.
 */
@Repository
@RequiredArgsConstructor
public class AdminUserQueryRepository {

    private static final String NAME_EXPRESSION = """
            CASE lower(r.role_code)
                WHEN 'customer' THEN c.customer_name
                WHEN 'admin' THEN a.admin_name
                ELSE e.employee_name
            END
            """;

    private static final String PHONE_EXPRESSION = """
            CASE lower(r.role_code)
                WHEN 'customer' THEN c.customer_phone
                WHEN 'admin' THEN a.admin_phone
                ELSE e.employee_phone
            END
            """;

    private static final String ADDRESS_EXPRESSION = """
            CASE lower(r.role_code)
                WHEN 'customer' THEN c.customer_address
                WHEN 'admin' THEN a.admin_address
                ELSE e.employee_address
            END
            """;

    private static final String FROM_AND_JOINS = """
            FROM users u
            JOIN roles r ON r.id = u.role_id AND r.is_deleted = false
            LEFT JOIN customers c ON c.user_id = u.id
            LEFT JOIN employees e ON e.user_id = u.id
            LEFT JOIN admins a ON a.user_id = u.id
            """;

    private static final String SELECT = """
            SELECT u.user_id,
                   u.user_email,
                   %s AS name,
                   %s AS phone,
                   %s AS address,
                   lower(r.role_code) AS role_code,
                   r.role_name AS role_name,
                   u.user_status AS status,
                   u.user_last_login AS last_login,
                   u.created_at AS created_at
            """.formatted(NAME_EXPRESSION, PHONE_EXPRESSION, ADDRESS_EXPRESSION);

    private static final RowMapper<Row> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp lastLogin = rs.getTimestamp("last_login");
        return new Row(
                rs.getString("user_id"),
                rs.getString("user_email"),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("address"),
                rs.getString("role_code"),
                rs.getString("role_name"),
                UserStatus.valueOf(rs.getString("status")),
                lastLogin == null ? null : lastLogin.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Page<Row> search(String keyword, String roleCode, String status, Pageable pageable) {
        int pageNumber = pageable.isPaged() ? Math.max(0, pageable.getPageNumber()) : 0;
        int pageSize = pageable.isPaged() ? Math.max(1, pageable.getPageSize()) : 20;

        String keywordFilter = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String roleFilter = roleCode == null ? "" : roleCode.trim().toLowerCase(Locale.ROOT);
        String statusFilter = status == null ? "" : status.trim();

        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = whereClause(keywordFilter, roleFilter, statusFilter, params);

        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) " + FROM_AND_JOINS + where, params, Long.class);
        long total = count == null ? 0 : count;

        List<Row> rows = List.of();
        if (total > 0) {
            MapSqlParameterSource dataParams = new MapSqlParameterSource(params.getValues())
                    .addValue("limit", pageSize)
                    .addValue("offset", (long) pageNumber * pageSize);
            rows = jdbcTemplate.query(
                    SELECT + FROM_AND_JOINS + where + orderBy(pageable)
                            + " LIMIT :limit OFFSET :offset",
                    dataParams, ROW_MAPPER);
        }

        return new PageImpl<>(rows, PageRequest.of(pageNumber, pageSize), total);
    }

    private static String whereClause(
            String keyword, String roleCode, String status, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE u.user_status <> 'DELETED'");

        if (!keyword.isBlank()) {
            where.append(" AND (lower(u.user_email) LIKE :keywordPattern ESCAPE '!'")
                    .append(" OR lower(").append(NAME_EXPRESSION)
                    .append(") LIKE :keywordPattern ESCAPE '!')");
            params.addValue("keywordPattern", "%" + escapeLike(keyword) + "%");
        }
        if (!roleCode.isBlank()) {
            where.append(" AND lower(r.role_code) = :roleCode");
            params.addValue("roleCode", roleCode);
        }
        if (!status.isBlank()) {
            where.append(" AND u.user_status = :status");
            params.addValue("status", status);
        }

        return where.toString();
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private String orderBy(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return " ORDER BY u.created_at DESC, u.id ASC";
        }

        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = switch (order.getProperty()) {
                case "email" -> "lower(u.user_email)";
                case "name" -> "lower(" + NAME_EXPRESSION + ")";
                case "roleCode" -> "lower(r.role_code)";
                case "status" -> "u.user_status";
                case "lastLogin" -> "u.user_last_login";
                case "createdAt" -> "u.created_at";
                default -> throw new IllegalArgumentException(
                        "unsupported sort field: " + order.getProperty());
            };
            parts.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        parts.add("u.id ASC");
        return " ORDER BY " + String.join(", ", parts);
    }

    public record Row(
            String userId,
            String email,
            String name,
            String phone,
            String address,
            String roleCode,
            String roleName,
            UserStatus status,
            java.time.Instant lastLogin,
            java.time.Instant createdAt) {
    }
}
