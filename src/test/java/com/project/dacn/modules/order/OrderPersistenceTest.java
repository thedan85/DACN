package com.project.dacn.modules.order;

import com.project.dacn.modules.auth.entity.Role;
import com.project.dacn.modules.auth.entity.User;
import com.project.dacn.modules.auth.repository.RoleRepository;
import com.project.dacn.modules.auth.repository.UserRepository;
import com.project.dacn.modules.catalog.entity.Product;
import com.project.dacn.modules.catalog.entity.Variant;
import com.project.dacn.modules.catalog.repository.ProductRepository;
import com.project.dacn.modules.catalog.repository.VariantRepository;
import com.project.dacn.modules.common.enums.PaymentMethod;
import com.project.dacn.modules.identity.entity.Customer;
import com.project.dacn.modules.identity.repository.CustomerRepository;
import com.project.dacn.modules.order.entity.Order;
import com.project.dacn.modules.order.entity.OrderItem;
import com.project.dacn.modules.order.entity.Payment;
import com.project.dacn.modules.order.repository.OrderItemRepository;
import com.project.dacn.modules.order.repository.OrderRepository;
import com.project.dacn.modules.order.repository.PaymentRepository;
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
        assertThat(item.getOrderItemId()).isNotNull();

        Payment payment = paymentRepository.save(Payment.builder()
                .order(order).method(PaymentMethod.COD)
                .amount(new BigDecimal("40.00")).build());
        assertThat(payment.getPaymentId()).isNotNull();
        assertThat(paymentRepository.findByOrder_OrderId(order.getOrderId())).isPresent();
    }
}
