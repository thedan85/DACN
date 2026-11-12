package com.project.dacn.dto.response.dashboard;

import com.project.dacn.common.constant.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One payload for the admin dashboard: KPIs, the revenue series, the order
 * status split and the low-stock list. The service assembles it from two
 * order windows and one catalog query.
 */
public record DashboardSummaryResponse(
        Instant generatedAt,
        LocalDate from,
        LocalDate to,
        Granularity granularity,
        MoneyKpi revenue,
        CountKpi orders,
        long activeProducts,
        LowStockSummary lowStock,
        List<RevenuePoint> revenueSeries,
        List<OrderStatusCount> orderStatus,
        CompletionRate completionRate,
        List<LowStockProduct> lowStockProducts) {

    public enum Granularity {
        DAY, WEEK
    }

    public record MoneyKpi(BigDecimal value, BigDecimal previous, BigDecimal changePct) {
    }

    public record CountKpi(long value, long previous, BigDecimal changePct) {
    }

    public record LowStockSummary(int threshold, long count) {
    }

    public record RevenuePoint(LocalDate date, BigDecimal revenue, long orders) {
    }

    public record OrderStatusCount(OrderStatus status, long count) {
    }

    public record CompletionRate(long completed, long total, BigDecimal pct) {
    }

    public record LowStockProduct(
            String productId,
            String slug,
            String name,
            String thumbnail,
            int stock) {
    }
}
