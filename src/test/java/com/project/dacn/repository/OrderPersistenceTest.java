package com.project.dacn.repository;

import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.order.Payment;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.order.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class OrderPersistenceTest {

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
    void persistOrderChainThroughBusinessKeys() {
        Role role = roleRepository.save(Role.builder().code("or1").name("R").build());
        User user = userRepository.save(User.builder().email("ord@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        Variant variant = variantRepository.save(Variant.builder().product(product)
                .sku("ORD-SKU-1").price(new BigDecimal("20.00")).stock(5).build());

        Order order = orderRepository.save(Order.builder()
                .customer(customer).total(new BigDecimal("40.00"))
                .address("123 Street").recipientName("C").phone("090").build());
        assertThat(order.getOrderId()).isNotNull();

        OrderItem item = orderItemRepository.save(OrderItem.builder()
                .order(order).variant(variant).quantity(2)
                .unitPrice(new BigDecimal("20.00")).build());

        Payment payment = paymentRepository.save(Payment.builder()
                .order(order).method(PaymentMethod.COD)
                .amount(new BigDecimal("40.00")).build());
        assertThat(payment.getPaymentId()).isNotNull();
        assertThat(paymentRepository.findByOrder_OrderId(order.getOrderId())).isPresent();
    }
}
