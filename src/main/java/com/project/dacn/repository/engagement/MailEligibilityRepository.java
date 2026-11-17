package com.project.dacn.repository.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One fresh, server-owned read taken immediately before an SMTP attempt. It
 * combines claim ownership, current recipient eligibility, fresh order/payment
 * truth and a fresh database clock in a single statement. No socket or mail
 * content is involved, and no row is locked across the read.
 */
@Repository
public class MailEligibilityRepository {

    public record FreshSendFacts(
            boolean ownershipValid,
            boolean recipientActive,
            String recipientEmail,
            OrderStatus orderStatus,
            PaymentStatus paymentStatus,
            Instant paymentExpiresAt,
            boolean deadlineActive,
            Instant databaseNow) {
    }

    private static final String FRESH_SQL = """
            select
              (d.state = 'IN_FLIGHT'
               and d.claim_token = :token
               and d.attempt_count = :attempt
               and d.completed_at is null
               and d.claim_expires_at is not null
               and d.claim_expires_at > clock_timestamp()) as ownership_valid,
              (u.id is not null
               and u.user_status = 'ACTIVE'
               and u.user_email is not null
               and btrim(u.user_email) <> '') as recipient_active,
              u.user_email as recipient_email,
              o.order_status,
              p.payment_status,
              o.payment_expires_at,
              (o.payment_expires_at is not null
               and o.stock_released_at is null
               and o.order_status in ('PENDING', 'CONFIRMED', 'SHIPPING')) as deadline_active,
              clock_timestamp() as db_now
            from email_deliveries d
            left join users u on u.id = :userId
            left join orders o on o.order_id = :orderId
            left join payments p on p.order_id = o.id
            where d.id = :deliveryId and d.event_id = :eventId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MailEligibilityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FreshSendFacts fresh(UUID eventId, long deliveryId, UUID claimToken, int attempt,
                                long recipientUserId, String orderId) {
        List<FreshSendFacts> rows = jdbc.query(FRESH_SQL, new MapSqlParameterSource()
                        .addValue("eventId", eventId)
                        .addValue("deliveryId", deliveryId)
                        .addValue("token", claimToken)
                        .addValue("attempt", attempt)
                        .addValue("userId", recipientUserId)
                        .addValue("orderId", orderId),
                (result, index) -> new FreshSendFacts(
                        result.getBoolean("ownership_valid"),
                        result.getBoolean("recipient_active"),
                        result.getString("recipient_email"),
                        enumOrNull(OrderStatus.class, result.getString("order_status")),
                        enumOrNull(PaymentStatus.class, result.getString("payment_status")),
                        instant(result, "payment_expires_at"),
                        result.getBoolean("deadline_active"),
                        instant(result, "db_now")));
        if (rows.isEmpty()) {
            return new FreshSendFacts(false, false, null, null, null, null, false, null);
        }
        return rows.getFirst();
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
