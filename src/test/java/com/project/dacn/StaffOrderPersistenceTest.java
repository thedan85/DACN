package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
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
import com.project.dacn.mapper.order.OrderMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

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
    Variant variant;
    Payment payment;

    @BeforeEach
    void seed() {
        Role role = roleRepository.save(Role.builder().code("stafford").name("R").build());
        User user = userRepository.save(User.builder()
                .email("stafford@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        variant = variantRepository.save(Variant.builder().product(product)
                .sku("STAFF-SKU").price(new BigDecimal("10.00")).stock(5).build());
        order = orderRepository.save(Order.builder()
                .customer(customer).total(new BigDecimal("20.00"))
                .address("addr").recipientName("C").phone("090").build());
        orderItemRepository.save(OrderItem.builder()
                .order(order).variant(variant).quantity(2)
                .unitPrice(new BigDecimal("10.00")).build());
        payment = paymentRepository.save(Payment.builder()
                .order(order).method(PaymentMethod.COD)
                .amount(new BigDecimal("20.00")).build());
        entityManager.flush();
    }

    @Test
    void rejectCancelsRestoresStockAndFailsPayment() {
        entityManager.flush();
        entityManager.clear();

        OrderResponse response = service.updateStatusByStaff(order.getOrderId(),
                new StaffOrderStatusUpdateRequest(OrderStatus.CANCELLED));
        entityManager.flush();
        entityManager.clear();

        assertThat(response.customerName()).isEqualTo("C");
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderRepository.findByOrderId(order.getOrderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(variantRepository.findById(variant.getId()).orElseThrow().getStock())
                .isEqualTo(7);
        assertThat(paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
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
