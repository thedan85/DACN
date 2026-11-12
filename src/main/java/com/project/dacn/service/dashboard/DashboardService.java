package com.project.dacn.service.dashboard;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.CompletionRate;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.CountKpi;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.Granularity;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.LowStockProduct;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.LowStockSummary;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.MoneyKpi;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.OrderStatusCount;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.RevenuePoint;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<Integer> SUPPORTED_DAYS = Set.of(7, 30, 90);
    private static final int WEEK_DAYS = 90;
    private static final int LOW_STOCK_THRESHOLD = 10;
    private static final int LOW_STOCK_LIST_LIMIT = 5;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    /**
     * Builds the whole dashboard in five reads: current orders, the previous
     * order window, active product count, the low-stock total, and the
     * low-stock products.
     *
     * <p>The order rows are lightweight — status, total and creation time —
     * and are bucketed here rather than with {@code date_trunc}: the stored
     * instants are UTC, so keeping the Vietnam day boundary in Java makes the
     * chart identical on PostgreSQL and H2.</p>
     *
     * <p>shortcut: one projection row per order in the window; move the
     * buckets into SQL once a 90-day window grows past ~50k orders.</p>
     */
    public DashboardSummaryResponse summary(int days) {
        if (!SUPPORTED_DAYS.contains(days)) {
            throw new IllegalArgumentException("days must be 7, 30, or 90");
        }

        LocalDate today = LocalDate.now(STORE_ZONE);
        LocalDate from = today.minusDays(days - 1L);
        Instant start = from.atStartOfDay(STORE_ZONE).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(STORE_ZONE).toInstant();
        Instant previousStart = from.minusDays(days).atStartOfDay(STORE_ZONE).toInstant();

        List<OrderRepository.DashboardOrderRow> current =
                orderRepository.findDashboardRows(start, end);
        List<OrderRepository.DashboardOrderRow> previous =
                orderRepository.findDashboardRows(previousStart, start);

        BigDecimal revenue = revenueOf(current);
        BigDecimal previousRevenue = revenueOf(previous);
        long completed = current.stream()
                .filter(row -> row.getStatus() == OrderStatus.COMPLETED)
                .count();

        Granularity granularity = days == WEEK_DAYS ? Granularity.WEEK : Granularity.DAY;
        long lowStockCount = productRepository.countLowStockProducts(
                ProductStatus.ACTIVE, LOW_STOCK_THRESHOLD);
        List<ProductRepository.LowStockProductView> lowStock =
                productRepository.findLowStockProducts(
                        ProductStatus.ACTIVE,
                        LOW_STOCK_THRESHOLD,
                        PageRequest.of(0, LOW_STOCK_LIST_LIMIT));

        return new DashboardSummaryResponse(
                Instant.now(),
                from,
                today,
                granularity,
                new MoneyKpi(revenue, previousRevenue,
                        percentageChange(revenue, previousRevenue)),
                new CountKpi(current.size(), previous.size(),
                        percentageChange(
                                BigDecimal.valueOf(current.size()),
                                BigDecimal.valueOf(previous.size()))),
                productRepository.countByStatus(ProductStatus.ACTIVE),
                new LowStockSummary(LOW_STOCK_THRESHOLD, lowStockCount),
                revenueSeries(current, from, today, granularity),
                orderStatusCounts(current),
                new CompletionRate(completed, current.size(),
                        rate(completed, current.size())),
                lowStock.stream()
                        .map(row -> new LowStockProduct(
                                row.getProductId(),
                                row.getSlug(),
                                row.getName(),
                                row.getThumbnail(),
                                row.getStock() == null ? 0 : row.getStock().intValue()))
                        .toList());
    }

    private static BigDecimal revenueOf(List<OrderRepository.DashboardOrderRow> rows) {
        return rows.stream()
                .filter(row -> row.getStatus() != OrderStatus.CANCELLED)
                .map(DashboardService::totalOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal totalOf(OrderRepository.DashboardOrderRow row) {
        return row.getTotal() == null ? BigDecimal.ZERO : row.getTotal();
    }

    private static List<RevenuePoint> revenueSeries(
            List<OrderRepository.DashboardOrderRow> rows,
            LocalDate from,
            LocalDate today,
            Granularity granularity) {
        LocalDate firstBucket = bucketDate(from, granularity);
        LocalDate lastBucket = bucketDate(today, granularity);
        int step = granularity == Granularity.WEEK ? 7 : 1;

        Map<LocalDate, Bucket> buckets = new TreeMap<>();
        for (LocalDate date = firstBucket; !date.isAfter(lastBucket); date = date.plusDays(step)) {
            buckets.put(date, new Bucket());
        }

        for (OrderRepository.DashboardOrderRow row : rows) {
            LocalDate date = row.getCreatedAt().atZone(STORE_ZONE).toLocalDate();
            Bucket bucket = buckets.get(bucketDate(date, granularity));
            if (bucket == null) {
                continue;
            }
            bucket.orders++;
            if (row.getStatus() != OrderStatus.CANCELLED) {
                bucket.revenue = bucket.revenue.add(totalOf(row));
            }
        }

        return buckets.entrySet().stream()
                .map(entry -> new RevenuePoint(
                        entry.getKey(), entry.getValue().revenue, entry.getValue().orders))
                .toList();
    }

    private static LocalDate bucketDate(LocalDate date, Granularity granularity) {
        return granularity == Granularity.WEEK
                ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : date;
    }

    private static List<OrderStatusCount> orderStatusCounts(
            List<OrderRepository.DashboardOrderRow> rows) {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            counts.put(status, 0L);
        }
        for (OrderRepository.DashboardOrderRow row : rows) {
            counts.merge(row.getStatus(), 1L, Long::sum);
        }

        return counts.entrySet().stream()
                .map(entry -> new OrderStatusCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static BigDecimal percentageChange(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous)
                .multiply(ONE_HUNDRED)
                .divide(previous, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(long completed, long total) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(completed)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private static final class Bucket {
        private BigDecimal revenue = BigDecimal.ZERO;
        private long orders;
    }
}
