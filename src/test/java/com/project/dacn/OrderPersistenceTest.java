package com.project.dacn;

import com.project.dacn.entity.Role;
import com.project.dacn.entity.User;
import com.project.dacn.repository.RoleRepository;
import com.project.dacn.repository.UserRepository;
import com.project.dacn.entity.Product;
import com.project.dacn.entity.Variant;
import com.project.dacn.repository.ProductRepository;
import com.project.dacn.repository.VariantRepository;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.entity.Customer;
import com.project.dacn.repository.CustomerRepository;
import com.project.dacn.entity.Order;
import com.project.dacn.entity.OrderItem;
import com.project.dacn.entity.Payment;
import com.project.dacn.repository.OrderItemRepository;
import com.project.dacn.repository.OrderRepository;
import com.project.dacn.repository.PaymentRepository;
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
