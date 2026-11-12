package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.Granularity;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.OrderStatusCount;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.RevenuePoint;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.service.dashboard.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Mock
    OrderRepository orderRepository;

    @Mock
    ProductRepository productRepository;

    DashboardService service;

    LocalDate today;

    @BeforeEach
    void setUp() {
        service = new DashboardService(orderRepository, productRepository);
        today = LocalDate.now(STORE_ZONE);
    }

    @Test
    void buildsKpisSeriesAndStatusCounts() {
        when(orderRepository.findDashboardRows(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(
                        row(OrderStatus.COMPLETED, "100.00", at(today.minusDays(2))),
                        row(OrderStatus.CANCELLED, "50.00", at(today.minusDays(1))),
                        row(OrderStatus.PENDING, "20.00", at(today))))
                .thenReturn(List.of(
                        row(OrderStatus.COMPLETED, "60.00", at(today.minusDays(9)))));
        when(productRepository.countLowStockProducts(ProductStatus.ACTIVE, 10)).thenReturn(6L);
        when(productRepository.findLowStockProducts(
                ProductStatus.ACTIVE, 10, PageRequest.of(0, 5)))
                .thenReturn(List.of(
                        lowStock("PRD_1", "P1", 3L),
                        lowStock("PRD_2", "P2", 4L),
                        lowStock("PRD_3", "P3", 5L),
                        lowStock("PRD_4", "P4", 6L),
                        lowStock("PRD_5", "P5", 7L)));
        when(productRepository.countByStatus(ProductStatus.ACTIVE)).thenReturn(4L);

        DashboardSummaryResponse summary = service.summary(7);

        assertThat(summary.from()).isEqualTo(today.minusDays(6));
        assertThat(summary.to()).isEqualTo(today);
        assertThat(summary.granularity()).isEqualTo(Granularity.DAY);
        assertThat(summary.revenue().value()).isEqualByComparingTo("120.00");
        assertThat(summary.revenue().previous()).isEqualByComparingTo("60.00");
        assertThat(summary.revenue().changePct()).isEqualByComparingTo("100.0");
        assertThat(summary.orders().value()).isEqualTo(3);
        assertThat(summary.orders().previous()).isEqualTo(1);
        assertThat(summary.orders().changePct()).isEqualByComparingTo("200.0");
        assertThat(summary.activeProducts()).isEqualTo(4);
        assertThat(summary.completionRate().completed()).isEqualTo(1);
        assertThat(summary.completionRate().pct()).isEqualByComparingTo("33.3");
        assertThat(summary.lowStock().threshold()).isEqualTo(10);
        assertThat(summary.lowStock().count()).isEqualTo(6);
        assertThat(summary.lowStockProducts()).hasSize(5);

        assertThat(summary.revenueSeries()).hasSize(7);
        RevenuePoint day = summary.revenueSeries().get(4);
        assertThat(day.date()).isEqualTo(today.minusDays(2));
        assertThat(day.revenue()).isEqualByComparingTo("100.00");
        assertThat(day.orders()).isEqualTo(1);

        assertThat(summary.orderStatus())
                .filteredOn(count -> count.status() == OrderStatus.CANCELLED)
                .singleElement()
                .extracting(OrderStatusCount::count)
                .isEqualTo(1L);
    }

    @Test
    void omitsChangePercentWhenPreviousWindowIsEmpty() {
        when(orderRepository.findDashboardRows(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(row(OrderStatus.COMPLETED, "10.00", at(today))))
                .thenReturn(List.of());
        when(productRepository.countLowStockProducts(ProductStatus.ACTIVE, 10)).thenReturn(0L);
        when(productRepository.findLowStockProducts(
                ProductStatus.ACTIVE, 10, PageRequest.of(0, 5))).thenReturn(List.of());
        when(productRepository.countByStatus(ProductStatus.ACTIVE)).thenReturn(0L);

        DashboardSummaryResponse summary = service.summary(30);

        assertThat(summary.revenue().changePct()).isNull();
        assertThat(summary.orders().changePct()).isNull();
        assertThat(summary.completionRate().pct()).isEqualByComparingTo("100.0");
        assertThat(summary.revenueSeries()).hasSize(30);
    }

    @Test
    void bucketsNinetyDaysByWeekStartingMonday() {
        LocalDate orderDate = today.minusDays(10)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant nearMidnight = orderDate.atTime(0, 30).atZone(STORE_ZONE).toInstant();
        when(orderRepository.findDashboardRows(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(row(OrderStatus.COMPLETED, "10.00", nearMidnight)))
                .thenReturn(List.of());
        when(productRepository.countLowStockProducts(ProductStatus.ACTIVE, 10)).thenReturn(0L);
        when(productRepository.findLowStockProducts(
                ProductStatus.ACTIVE, 10, PageRequest.of(0, 5))).thenReturn(List.of());
        when(productRepository.countByStatus(ProductStatus.ACTIVE)).thenReturn(0L);

        DashboardSummaryResponse summary = service.summary(90);

        LocalDate expectedBucket = orderDate.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastBucket = today.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(summary.granularity()).isEqualTo(Granularity.WEEK);
        assertThat(summary.revenueSeries()).isNotEmpty()
                .allSatisfy(point -> assertThat(point.date().getDayOfWeek())
                        .isEqualTo(DayOfWeek.MONDAY));
        assertThat(summary.revenueSeries()
                .get(summary.revenueSeries().size() - 1).date()).isEqualTo(lastBucket);
        assertThat(summary.revenueSeries())
                .filteredOn(point -> point.date().equals(expectedBucket))
                .singleElement()
                .satisfies(point -> {
                    assertThat(point.revenue()).isEqualByComparingTo("10.00");
                    assertThat(point.orders()).isEqualTo(1);
                });
    }

    @Test
    void returnsZeroKpisWhenWindowHasNoOrders() {
        when(orderRepository.findDashboardRows(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(productRepository.countLowStockProducts(ProductStatus.ACTIVE, 10)).thenReturn(0L);
        when(productRepository.findLowStockProducts(
                ProductStatus.ACTIVE, 10, PageRequest.of(0, 5))).thenReturn(List.of());
        when(productRepository.countByStatus(ProductStatus.ACTIVE)).thenReturn(0L);

        DashboardSummaryResponse summary = service.summary(7);

        assertThat(summary.revenue().value()).isEqualByComparingTo("0");
        assertThat(summary.revenue().changePct()).isNull();
        assertThat(summary.orders().value()).isZero();
        assertThat(summary.orders().changePct()).isNull();
        assertThat(summary.completionRate().completed()).isZero();
        assertThat(summary.completionRate().pct()).isEqualByComparingTo("0.0");
        assertThat(summary.revenueSeries()).hasSize(7)
                .allSatisfy(point -> {
                    assertThat(point.revenue()).isEqualByComparingTo("0");
                    assertThat(point.orders()).isZero();
                });
    }

    @Test
    void rejectsUnsupportedRange() {
        assertThatThrownBy(() -> service.summary(14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("days must be 7, 30, or 90");
    }

    private static Instant at(LocalDate date) {
        return date.atTime(10, 0).atZone(STORE_ZONE).toInstant();
    }

    private static OrderRepository.DashboardOrderRow row(
            OrderStatus status, String total, Instant createdAt) {
        return new OrderRepository.DashboardOrderRow() {
            @Override
            public OrderStatus getStatus() {
                return status;
            }

            @Override
            public BigDecimal getTotal() {
                return new BigDecimal(total);
            }

            @Override
            public Instant getCreatedAt() {
                return createdAt;
            }
        };
    }

    private static ProductRepository.LowStockProductView lowStock(
            String productId, String name, long stock) {
        return new ProductRepository.LowStockProductView() {
            @Override
            public String getProductId() {
                return productId;
            }

            @Override
            public String getSlug() {
                return productId.toLowerCase();
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getThumbnail() {
                return null;
            }

            @Override
            public Long getStock() {
                return stock;
            }
        };
    }
}
