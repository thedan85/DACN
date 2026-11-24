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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class OrderGhnReconciliationRepositoryTest {

    private static final List<OrderStatus> NON_TERMINAL =
            List.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPING);

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Test
    void selectsNonTerminalNonBlankCodesWithKeysetCursorAndPageCap() {
        Role role = roleRepository.save(Role.builder().code("ghn-reconcile").name("R").build());
        User user = userRepository.save(User.builder()
                .email("ghn-reconcile@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());

        save(customer, "GHN-1", OrderStatus.CONFIRMED);
        Order second = save(customer, "GHN-2", OrderStatus.SHIPPING);
        save(customer, "GHN-3", OrderStatus.CONFIRMED);
        Order fourth = save(customer, "GHN-4", OrderStatus.SHIPPING);
        save(customer, "GHN-5", OrderStatus.COMPLETED);
        save(customer, "GHN-6", OrderStatus.PENDING);
        save(customer, null, OrderStatus.CONFIRMED);
        save(customer, "", OrderStatus.CONFIRMED);

        assertThat(orderRepository.findGhnReconciliationCandidates(NON_TERMINAL, 0L, PageRequest.of(0, 10)))
                .extracting(Order::getGhnOrderCode)
                .containsExactly("GHN-1", "GHN-2", "GHN-3", "GHN-4");
        assertThat(orderRepository.findGhnReconciliationCandidates(NON_TERMINAL, 0L, PageRequest.of(0, 2)))
                .extracting(Order::getGhnOrderCode).containsExactly("GHN-1", "GHN-2");
        assertThat(orderRepository.findGhnReconciliationCandidates(
                NON_TERMINAL, second.getId(), PageRequest.of(0, 2)))
                .extracting(Order::getGhnOrderCode).containsExactly("GHN-3", "GHN-4");
        assertThat(orderRepository.findGhnReconciliationCandidates(
                NON_TERMINAL, fourth.getId(), PageRequest.of(0, 2))).isEmpty();
    }

    private Order save(Customer customer, String ghnCode, OrderStatus status) {
        return orderRepository.saveAndFlush(Order.builder()
                .customer(customer).status(status).ghnOrderCode(ghnCode)
                .total(new BigDecimal("1000.00"))
                .address("addr").recipientName("C").phone("090").build());
    }
}
