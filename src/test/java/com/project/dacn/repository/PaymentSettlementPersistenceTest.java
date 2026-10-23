package com.project.dacn.repository;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the conditional UPDATE that makes concurrent VNPay IPNs settle once:
 * the second settle hits zero rows because the status is no longer PENDING.
 */
@DataJpaTest
@ActiveProfiles("test")
class PaymentSettlementPersistenceTest {

    @Autowired
    RoleRepository roleRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CustomerRepository customerRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    PaymentRepository paymentRepository;

    Order order;
    Payment payment;

    @BeforeEach
    void seed() {
        Role role = roleRepository.save(Role.builder().code("paysettle").name("R").build());
        User user = userRepository.save(User.builder()
                .email("paysettle@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());
        productRepository.save(Product.builder().name("P").description("d").build());
        order = orderRepository.save(Order.builder()
                .customer(customer).total(new BigDecimal("150000.00"))
                .address("addr").recipientName("C").phone("090").build());
        payment = paymentRepository.save(Payment.builder()
                .order(order).method(PaymentMethod.E_WALLET)
                .amount(new BigDecimal("150000.00")).build());
    }

    @Test
    void settleWinsOnlyOnce() {
        int first = paymentRepository.settle(payment.getPaymentId(), PaymentStatus.PENDING,
                PaymentStatus.CONFIRMED, "TX1", Instant.now());
        int second = paymentRepository.settle(payment.getPaymentId(), PaymentStatus.PENDING,
                PaymentStatus.CONFIRMED, "TX1", Instant.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        Payment fresh = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(fresh.getTransactionId()).isEqualTo("TX1");
        assertThat(fresh.getPaidAt()).isNotNull();
    }

    @Test
    void failedIpnCannotDowngradeConfirmedPayment() {
        paymentRepository.settle(payment.getPaymentId(), PaymentStatus.PENDING,
                PaymentStatus.CONFIRMED, "TX1", Instant.now());

        int downgrade = paymentRepository.settle(payment.getPaymentId(), PaymentStatus.PENDING,
                PaymentStatus.FAILED, null, Instant.now());

        assertThat(downgrade).isZero();
        Payment fresh = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void orderStatusUpdateIsConditional() {
        int first = orderRepository.updateStatus(order.getOrderId(),
                OrderStatus.PENDING, OrderStatus.CONFIRMED);
        int second = orderRepository.updateStatus(order.getOrderId(),
                OrderStatus.PENDING, OrderStatus.CANCELLED);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(orderRepository.findByOrderId(order.getOrderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
    }
}
