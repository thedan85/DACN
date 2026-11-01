package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class CustomerOrderSearchPersistenceTest {

    @Autowired OrderRepository orderRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRepository userRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager entityManager;

    @Test
    void filtersByCustomerStatusAndOrderIdNewestFirst() {
        Customer jane = customer("jane@x.com");
        Customer john = customer("john@x.com");
        order(jane, "ORD_ALPHA", OrderStatus.PENDING);
        order(jane, "ORD_BETA", OrderStatus.CONFIRMED);
        order(john, "ORD_ALPHA_OTHER", OrderStatus.CONFIRMED);

        Page<Order> all = orderRepository.searchForCustomer(
                jane.getId(), "", null, null, PageRequest.of(0, 10));
        assertThat(all.getContent()).extracting(Order::getOrderId)
                .containsExactly("ORD_BETA", "ORD_ALPHA");

        Page<Order> confirmed = orderRepository.searchForCustomer(
                jane.getId(), "", OrderStatus.CONFIRMED, null, PageRequest.of(0, 10));
        assertThat(confirmed.getContent()).extracting(Order::getOrderId)
                .containsExactly("ORD_BETA");

        Page<Order> searched = orderRepository.searchForCustomer(
                jane.getId(), "alpha", null, null, PageRequest.of(0, 10));
        assertThat(searched.getContent()).extracting(Order::getOrderId)
                .containsExactly("ORD_ALPHA");
        assertThat(orderRepository.searchForCustomer(jane.getId(), "Recipient", null,
                null, PageRequest.of(0, 10))).isEmpty();
        assertThat(orderRepository.searchForCustomer(jane.getId(), "090", null,
                null, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void countsAndPagesOnlyMatchingCustomerOrders() {
        Customer jane = customer("pages@x.com");
        Customer john = customer("other@x.com");
        order(jane, "ORD_MATCH_1", OrderStatus.CONFIRMED);
        order(jane, "ORD_MATCH_2", OrderStatus.CONFIRMED);
        order(jane, "ORD_MATCH_3", OrderStatus.CONFIRMED);
        order(jane, "ORD_MATCH_PENDING", OrderStatus.PENDING);
        order(jane, "ORD_UNRELATED", OrderStatus.CONFIRMED);
        order(john, "ORD_MATCH_OTHER", OrderStatus.CONFIRMED);

        Page<Order> first = orderRepository.searchForCustomer(jane.getId(), "match",
                OrderStatus.CONFIRMED, null, PageRequest.of(0, 2));
        assertThat(first.getContent()).extracting(Order::getOrderId)
                .containsExactly("ORD_MATCH_3", "ORD_MATCH_2");
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        Page<Order> second = orderRepository.searchForCustomer(jane.getId(), "match",
                OrderStatus.CONFIRMED, null, PageRequest.of(1, 2));
        assertThat(second.getContent()).extracting(Order::getOrderId)
                .containsExactly("ORD_MATCH_1");
        assertThat(second.getTotalElements()).isEqualTo(3);
    }

    @Test
    void createdAfterIsInclusiveAndExcludesOlderOrders() {
        Customer jane = customer("dates@x.com");
        Order old = order(jane, "ORD_OLD", OrderStatus.PENDING);
        Order recent = order(jane, "ORD_RECENT", OrderStatus.PENDING);
        order(customer("dates-other@x.com"), "ORD_OTHER", OrderStatus.PENDING);
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");

        orderRepository.flush();
        jdbc.update("update orders set created_at = ? where id = ?",
                Timestamp.from(Instant.parse("2025-12-31T23:59:59Z")), old.getId());
        jdbc.update("update orders set created_at = ? where id = ?",
                Timestamp.from(cutoff), recent.getId());
        entityManager.clear();

        Page<Order> page = orderRepository.searchForCustomer(
                jane.getId(), "", null, cutoff, PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(Order::getOrderId)
                .containsExactly("ORD_RECENT");
    }

    private Customer customer(String email) {
        Role role = roleRepository.save(Role.builder().code(email).name("Customer").build());
        User user = userRepository.save(User.builder()
                .email(email).passwordHash("h").role(role).build());
        return customerRepository.save(Customer.builder()
                .user(user).name("Customer").phone("090").build());
    }

    private Order order(Customer customer, String orderId, OrderStatus status) {
        return orderRepository.save(Order.builder().customer(customer).orderId(orderId)
                .status(status).total(new BigDecimal("10000.00"))
                .address("addr").recipientName("Recipient").phone("090").build());
    }
}
