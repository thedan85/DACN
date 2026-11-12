package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.service.dashboard.DashboardService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(DashboardService.class)
class DashboardPersistenceTest {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    DashboardService service;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    VariantRepository variantRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CustomerRepository customerRepository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager entityManager;

    Customer customer;

    @BeforeEach
    void seedCustomer() {
        Role role = roleRepository.save(Role.builder().code("dash").name("Customer").build());
        User user = userRepository.save(User.builder()
                .email("dash@x.com").passwordHash("h").role(role).build());
        customer = customerRepository.save(Customer.builder()
                .user(user).name("Dashboard Customer").phone("090").build());
    }

    @Test
    void dashboardRowsUseHalfOpenWindow() {
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        Instant end = Instant.parse("2026-11-08T00:00:00Z");
        Order beforeStart = order(OrderStatus.PENDING, "10.00");
        Order atStart = order(OrderStatus.PENDING, "20.00");
        Order beforeEnd = order(OrderStatus.PENDING, "30.00");
        Order atEnd = order(OrderStatus.PENDING, "40.00");
        setCreatedAt(beforeStart, start.minusSeconds(1));
        setCreatedAt(atStart, start);
        setCreatedAt(beforeEnd, end.minusSeconds(1));
        setCreatedAt(atEnd, end);

        List<OrderRepository.DashboardOrderRow> rows =
                orderRepository.findDashboardRows(start, end);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(OrderRepository.DashboardOrderRow::getTotal)
                .containsExactlyInAnyOrder(new BigDecimal("20.00"), new BigDecimal("30.00"));
    }

    @Test
    void lowStockSumsLiveVariantsAndSkipsNonActiveProducts() {
        product("Alpha", ProductStatus.ACTIVE, 6, 5);
        product("Bravo", ProductStatus.ACTIVE, 4, 6);
        Product charlie = product("Charlie", ProductStatus.ACTIVE, 2);
        Product echo = product("Echo", ProductStatus.ACTIVE);
        product("Delta", ProductStatus.DRAFT, 0);
        product("Foxtrot", ProductStatus.ACTIVE, 10);
        variantRepository.save(Variant.builder().product(charlie).sku("DASH-DELETED")
                .price(new BigDecimal("10.00")).stock(50).deleted(true).build());
        entityManager.flush();

        List<ProductRepository.LowStockProductView> rows =
                productRepository.findLowStockProducts(
                        ProductStatus.ACTIVE, 10, PageRequest.of(0, 5));

        assertThat(rows).extracting(ProductRepository.LowStockProductView::getName)
                .containsExactly("Echo", "Charlie", "Bravo", "Foxtrot");
        assertThat(rows).extracting(ProductRepository.LowStockProductView::getStock)
                .containsExactly(0L, 2L, 10L, 10L);
        assertThat(echo.getId()).isNotNull();
        assertThat(productRepository.countByStatus(ProductStatus.ACTIVE)).isEqualTo(5);
        assertThat(productRepository.countLowStockProducts(ProductStatus.ACTIVE, 10)).isEqualTo(4);
    }

    @Test
    void summaryReadsRevenueStatusesAndPreviousWindow() {
        Order completed = order(OrderStatus.COMPLETED, "100.00");
        Order cancelled = order(OrderStatus.CANCELLED, "50.00");
        Order pending = order(OrderStatus.PENDING, "20.00");
        Order previous = order(OrderStatus.COMPLETED, "1000.00");
        LocalDate today = LocalDate.now(STORE_ZONE);
        setCreatedAt(completed, at(today.minusDays(2)));
        setCreatedAt(cancelled, at(today.minusDays(1)));
        setCreatedAt(pending, at(today));
        setCreatedAt(previous, at(today.minusDays(10)));
        product("Golf", ProductStatus.ACTIVE, 4);
        entityManager.flush();

        DashboardSummaryResponse summary = service.summary(7);

        assertThat(summary.revenue().value()).isEqualByComparingTo("120.00");
        assertThat(summary.revenue().previous()).isEqualByComparingTo("1000.00");
        assertThat(summary.revenue().changePct()).isEqualByComparingTo("-88.0");
        assertThat(summary.orders().value()).isEqualTo(3);
        assertThat(summary.orders().previous()).isEqualTo(1);
        assertThat(summary.completionRate().completed()).isEqualTo(1);
        assertThat(summary.completionRate().pct()).isEqualByComparingTo("33.3");
        assertThat(summary.revenueSeries()).hasSize(7)
                .allSatisfy(point -> assertThat(point.orders()).isGreaterThanOrEqualTo(0));
        assertThat(summary.revenueSeries().stream()
                .map(DashboardSummaryResponse.RevenuePoint::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("120.00");
        assertThat(summary.orderStatus())
                .filteredOn(count -> count.status() == OrderStatus.CANCELLED)
                .singleElement()
                .extracting(DashboardSummaryResponse.OrderStatusCount::count)
                .isEqualTo(1L);
        assertThat(summary.lowStock().count()).isEqualTo(1);
        assertThat(summary.lowStockProducts()).extracting(
                        DashboardSummaryResponse.LowStockProduct::name)
                .containsExactly("Golf");
    }

    private Order order(OrderStatus status, String total) {
        return orderRepository.save(Order.builder()
                .customer(customer).status(status).total(new BigDecimal(total))
                .address("addr").recipientName("Recipient").phone("090").build());
    }

    private void setCreatedAt(Order order, Instant createdAt) {
        orderRepository.flush();
        jdbc.update("update orders set created_at = ? where id = ?",
                Timestamp.from(createdAt), order.getId());
        entityManager.clear();
    }

    private Product product(String name, ProductStatus status, int... stocks) {
        Product product = productRepository.save(Product.builder()
                .name(name).description("d").status(status).build());
        for (int i = 0; i < stocks.length; i++) {
            variantRepository.save(Variant.builder().product(product)
                    .sku("DASH-" + name.toUpperCase() + "-" + i)
                    .price(new BigDecimal("10.00")).stock(stocks[i]).build());
        }
        return product;
    }

    private static Instant at(LocalDate date) {
        return date.atTime(10, 0).atZone(STORE_ZONE).toInstant();
    }
}
