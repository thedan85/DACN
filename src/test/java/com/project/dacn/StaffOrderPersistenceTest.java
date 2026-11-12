package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.order.StaffOrderStatusUpdateRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.OrderService;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({OrderService.class, StaffOrderPersistenceTest.MapperConfig.class})
class StaffOrderPersistenceTest {

    @TestConfiguration
    static class MapperConfig {
        @Bean
        OrderMapper orderMapper() {
            return Mappers.getMapper(OrderMapper.class);
        }

        @Bean
        PaymentMapper paymentMapper() {
            return Mappers.getMapper(PaymentMapper.class);
        }
    }

    @Autowired
    OrderService service;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CustomerRepository customerRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    VariantRepository variantRepository;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    OrderItemRepository orderItemRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    EntityManager entityManager;

    Order order;
    Order otherOrder;
    Variant variant;
    Payment payment;

    @BeforeEach
    void seed() {
        Role role = roleRepository.save(Role.builder().code("stafford").name("R").build());
        User user = userRepository.save(User.builder()
                .email("stafford@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("Alpha Customer").phone("0901234567").build());
        User otherUser = userRepository.save(User.builder()
                .email("beta@x.com").passwordHash("h").role(role).build());
        Customer otherCustomer = customerRepository.save(Customer.builder()
                .user(otherUser).name("Beta Customer").phone("0917654321").build());
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        variant = variantRepository.save(Variant.builder().product(product)
                .sku("STAFF-SKU").price(new BigDecimal("10.00")).stock(5).build());
        order = orderRepository.save(Order.builder()
                .customer(customer).total(new BigDecimal("20.00"))
                .address("addr").recipientName("Alpha Customer").phone("0901234567").build());
        otherOrder = orderRepository.save(Order.builder()
                .customer(otherCustomer).total(new BigDecimal("30.00"))
                .address("addr2").recipientName("Beta Customer").phone("0917654321").build());
        orderItemRepository.save(OrderItem.builder()
                .order(order).variant(variant).quantity(2)
                .unitPrice(new BigDecimal("10.00")).build());
        payment = paymentRepository.save(Payment.builder()
                .order(order).method(PaymentMethod.COD)
                .amount(new BigDecimal("20.00")).build());
        entityManager.flush();
    }

    @Test
    void reservationFieldsRoundTripAndRevisionRemainsDatabaseOwned() {
        Instant deadline = Instant.parse("2026-11-12T12:15:00Z");
        Variant inserted = variantRepository.save(Variant.builder().product(variant.getProduct())
                .sku("REVISION-INSERT").price(BigDecimal.TEN).stockRevision(99).build());
        order.setPaymentExpiresAt(deadline);
        order.setCancellationReason(OrderEventReason.STAFF_REJECTED);
        order.setStockReleasedAt(deadline);
        entityManager.flush();
        entityManager.clear();

        assertThat(variantRepository.findById(inserted.getId()).orElseThrow().getStockRevision()).isZero();
        Order persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getPaymentExpiresAt()).isEqualTo(deadline);
        assertThat(persisted.getCancellationReason()).isEqualTo(OrderEventReason.STAFF_REJECTED);
        assertThat(persisted.getStockReleasedAt()).isEqualTo(deadline);
        assertThat(orderRepository.findById(otherOrder.getId()).orElseThrow().getPaymentExpiresAt()).isNull();
        Variant storedVariant = variantRepository.findById(variant.getId()).orElseThrow();
        assertThat(storedVariant.getStockRevision()).isZero();
        entityManager.createNativeQuery("update variants set stock_revision = 7 where id = :id")
                .setParameter("id", variant.getId()).executeUpdate();
        storedVariant.setStockRevision(99);
        storedVariant.setSku("STAFF-SKU-EDITED");
        entityManager.flush();
        entityManager.clear();
        assertThat(variantRepository.findById(variant.getId()).orElseThrow().getStockRevision()).isEqualTo(7);
        OrderResponse response = service.get(order.getCustomer().getId(), order.getOrderId());
        assertThat(response.paymentExpiresAt()).isEqualTo(deadline);
        assertThat(response.cancellationReason()).isEqualTo(OrderEventReason.STAFF_REJECTED);
    }

    @Test
    void rejectCancelsRestoresStockAndFailsPayment() {
        entityManager.flush();
        entityManager.clear();

        OrderResponse response = service.updateStatusByStaff(order.getOrderId(),
                new StaffOrderStatusUpdateRequest(OrderStatus.CANCELLED));
        entityManager.flush();
        entityManager.clear();

        assertThat(response.customerName()).isEqualTo("Alpha Customer");
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderRepository.findByOrderId(order.getOrderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(variantRepository.findById(variant.getId()).orElseThrow().getStock())
                .isEqualTo(7);
        assertThat(paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void customerHistoryLoadsItemsInOneBatch() {
        Long customerId = order.getCustomer().getId();
        for (int i = 0; i < 4; i++) {
            Order extra = orderRepository.save(Order.builder()
                    .customer(order.getCustomer()).total(new BigDecimal("10.00"))
                    .address("addr").recipientName("C").phone("090").build());
            orderItemRepository.save(OrderItem.builder()
                    .order(extra).variant(variant).quantity(1)
                    .unitPrice(new BigDecimal("10.00")).build());
        }
        entityManager.flush();
        entityManager.clear();
        Statistics stats = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        service.list(customerId, null, order.getOrderId(), null, PageRequest.of(0, 20));
        long singleOrderStatements = stats.getPrepareStatementCount();
        assertThat(singleOrderStatements).isLessThanOrEqualTo(7);
        entityManager.clear();
        stats.clear();

        PageResponse<OrderResponse> page = service.list(
                customerId, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(5)
                .allSatisfy(o -> assertThat(o.items()).hasSize(1));
        // Page, items with attributes, order-item media.
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(4);
        assertThat(singleOrderStatements).isLessThanOrEqualTo(4);
    }

    @Test
    void staffListLoadsEnrichmentInBatches() {
        entityManager.flush();
        entityManager.clear();
        Statistics stats = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        PageResponse<OrderResponse> page = service.listForStaff(
                null, "alpha", PageRequest.of(0, 1));

        assertThat(page.getContent()).singleElement()
                .satisfies(o -> assertThat(o.items()).hasSize(1));
        // Page, count, items with attributes, order-item media.
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(4);
    }

    @Test
    void customerOrderDetailLoadsEnrichmentInBatches() {
        Long customerId = order.getCustomer().getId();
        entityManager.flush();
        entityManager.clear();
        Statistics stats = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        OrderResponse response = service.get(customerId, order.getOrderId());

        assertThat(response.items()).hasSize(1);
        // Order, customer and payment share one query; three queries batch
        // the enrichment.
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    @Test
    void staffSearchMatchesOrderIdContactFieldsAndStatus() {
        var page = PageRequest.of(0, 20);

        assertThat(service.listForStaff(null, order.getOrderId(), page).getContent())
                .extracting(OrderResponse::orderId).containsExactly(order.getOrderId());
        assertThat(service.listForStaff(null, "alpha", page).getContent())
                .extracting(OrderResponse::orderId).containsExactly(order.getOrderId());
        assertThat(service.listForStaff(null, "0901234567", page).getContent())
                .extracting(OrderResponse::orderId).containsExactly(order.getOrderId());
        assertThat(service.listForStaff(null, "stafford@", page).getContent())
                .extracting(OrderResponse::orderId).containsExactly(order.getOrderId());
        assertThat(service.listForStaff(null, "beta", page).getContent())
                .extracting(OrderResponse::orderId).containsExactly(otherOrder.getOrderId());
        assertThat(service.listForStaff(OrderStatus.CONFIRMED, "alpha", page).getContent())
                .isEmpty();
    }

    @Test
    void acceptConfirmsOrderWithoutTouchingStock() {
        service.updateStatusByStaff(order.getOrderId(),
                new StaffOrderStatusUpdateRequest(OrderStatus.CONFIRMED));
        entityManager.flush();
        entityManager.clear();

        assertThat(orderRepository.findByOrderId(order.getOrderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(variantRepository.findById(variant.getId()).orElseThrow().getStock())
                .isEqualTo(5);
        assertThat(paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }
}
