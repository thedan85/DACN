package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.shipping.ghn.GhnClient;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.project.dacn.integration.shipping.ghn.GhnStatusMapper.Event.CANCELLED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two cancel callbacks must restock once: the order row lock serializes them
 * and the loser sees CANCELLED.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({StaffOrderPersistenceTest.MapperConfig.class, OrderService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GhnWebhookConcurrencyTest {

    @MockitoBean
    GhnClient ghnClient;

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

    @Test
    void concurrentCancelRestocksOnce() throws Exception {
        Role role = roleRepository.save(Role.builder().code("ghn-race").name("R").build());
        User user = userRepository.save(User.builder()
                .email("ghn-race@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        Variant variant = variantRepository.save(Variant.builder().product(product)
                .sku("GHN-RACE-1").price(new BigDecimal("1000.00")).stock(3).build());
        Order order = orderRepository.saveAndFlush(Order.builder()
                .customer(customer).status(OrderStatus.CONFIRMED)
                .total(new BigDecimal("2000.00"))
                .address("addr").recipientName("C").phone("090")
                .ghnOrderCode("GHN-1").build());
        orderItemRepository.saveAndFlush(OrderItem.builder()
                .order(order).variant(variant).quantity(2)
                .unitPrice(new BigDecimal("1000.00")).build());
        paymentRepository.saveAndFlush(Payment.builder()
                .order(order).method(PaymentMethod.COD).status(PaymentStatus.PENDING)
                .amount(new BigDecimal("2000.00")).build());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Void> attempt = () -> {
            start.await();
            service.applyGhnEvent("GHN-1", null, CANCELLED);
            return null;
        };
        try {
            Future<Void> first = pool.submit(attempt);
            Future<Void> second = pool.submit(attempt);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(variantRepository.findById(variant.getId()).orElseThrow().getStock())
                .isEqualTo(5);
        assertThat(orderRepository.findByOrderId(order.getOrderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findByOrder_OrderId(order.getOrderId())
                .orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    // This class commits rows to the shared in-memory database.
    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        variantRepository.deleteAll();
        productRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
    }
}
