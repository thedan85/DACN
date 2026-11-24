package com.project.dacn.repository.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Restricted operator evidence only; never expose this inventory through a public API or logs. */
@Repository
@RequiredArgsConstructor
public class LegacyReservationQueries {

    private static final String INVENTORY_SQL = """
            select o.id, o.order_id, o.order_status, o.order_total, o.created_at,
                   o.payment_expires_at, o.cancellation_reason, o.stock_released_at,
                   p.payment_id, p.payment_method, p.payment_status, p.payment_amount,
                   p.payment_transaction_id, p.paid_at,
                   items.item_count, items.item_quantity
            from orders o
            left join payments p on p.order_id = o.id
            left join lateral (
                select count(*) as item_count, coalesce(sum(i.quantity), 0) as item_quantity
                from order_items i where i.order_id = o.id
            ) items on true
            order by o.id
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * One read snapshot of all observed pairs, including missing payment/item evidence.
     * Recommendations are review queues, not approval, proof of money, or lifecycle decisions.
     */
    @Transactional(readOnly = true)
    public Inventory inventory() {
        List<OrderEvidence> orders = jdbcTemplate.query(
                connection -> connection.prepareStatement(INVENTORY_SQL),
                (row, index) -> new OrderEvidence(row.getLong("id"), row.getString("order_id"),
                        OrderStatus.valueOf(row.getString("order_status")), row.getBigDecimal("order_total"),
                        instant(row, "created_at"), instant(row, "payment_expires_at"),
                        row.getString("cancellation_reason"), instant(row, "stock_released_at"),
                        row.getString("payment_id"), method(row.getString("payment_method")),
                        status(row.getString("payment_status")), row.getBigDecimal("payment_amount"),
                        row.getString("payment_transaction_id"), instant(row, "paid_at"),
                        row.getLong("item_count"), row.getLong("item_quantity")));
        Map<StatusPair, Long> counts = new LinkedHashMap<>();
        for (OrderEvidence order : orders) {
            counts.merge(new StatusPair(order.orderStatus(), order.paymentMethod(), order.paymentStatus()), 1L, Long::sum);
        }
        return new Inventory(List.copyOf(orders), Map.copyOf(counts),
                orders.stream().filter(OrderEvidence::blocksActivation).count());
    }

    public record Inventory(List<OrderEvidence> orders, Map<StatusPair, Long> countsByPair,
                            long unresolvedConfirmedUnpaidCount) { }

    /** Null method/status explicitly represents a missing payment, not COD or PENDING. */
    public record StatusPair(OrderStatus orderStatus, PaymentMethod paymentMethod, PaymentStatus paymentStatus) { }

    public enum Disposition {
        COD_UNCHANGED, PRESERVE_RECEIVED_MONEY, PENDING_RECONCILIATION, BLOCK_ACTIVATION, QUARANTINE
    }

    public enum EvidenceAvailability { UNKNOWN, RECORDED }

    public record OrderEvidence(long id, String orderId, OrderStatus orderStatus, BigDecimal orderTotal,
                                Instant createdAt, Instant paymentExpiresAt, String cancellationReason,
                                Instant stockReleasedAt, String paymentId, PaymentMethod paymentMethod,
                                PaymentStatus paymentStatus, BigDecimal paymentAmount, String providerTransactionId,
                                Instant paidAt, long itemCount, long itemQuantity) {

        /** A pending/failed record is not proof of no money; this conflicting state must be resolved. */
        public boolean blocksActivation() {
            return paymentMethod != null && paymentMethod != PaymentMethod.COD
                    && orderStatus == OrderStatus.CONFIRMED
                    && (paymentStatus == PaymentStatus.PENDING || paymentStatus == PaymentStatus.FAILED);
        }

        public Disposition disposition() {
            if (blocksActivation()) {
                return Disposition.BLOCK_ACTIVATION;
            }
            if (paymentMethod == null || itemCount == 0) {
                return Disposition.QUARANTINE;
            }
            if (paymentMethod == PaymentMethod.COD) {
                return Disposition.COD_UNCHANGED;
            }
            if (paymentStatus == PaymentStatus.CONFIRMED || paymentStatus == PaymentStatus.REFUND_REQUIRED) {
                return Disposition.PRESERVE_RECEIVED_MONEY;
            }
            if (orderStatus == OrderStatus.PENDING && paymentStatus == PaymentStatus.PENDING
                    && stockReleasedAt == null && cancellationReason == null) {
                return Disposition.PENDING_RECONCILIATION;
            }
            return Disposition.QUARANTINE;
        }

        public EvidenceAvailability releaseEvidence() {
            return stockReleasedAt == null ? EvidenceAvailability.UNKNOWN : EvidenceAvailability.RECORDED;
        }

        /** No authoritative fulfilment record exists in this schema; status is not proof. */
        public EvidenceAvailability fulfilmentEvidence() {
            return EvidenceAvailability.UNKNOWN;
        }

        public EvidenceAvailability issuedUrlEvidence() {
            return EvidenceAvailability.UNKNOWN;
        }

        /** Neither order timestamps nor payment status establish a last issued URL's expiry. */
        public Instant latestPossibleIssuedUrlExpiry() {
            return null;
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static PaymentMethod method(String value) {
        return value == null ? null : PaymentMethod.valueOf(value);
    }

    private static PaymentStatus status(String value) {
        return value == null ? null : PaymentStatus.valueOf(value);
    }
}
