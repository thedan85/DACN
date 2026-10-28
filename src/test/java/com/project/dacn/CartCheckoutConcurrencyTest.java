package com.project.dacn;

import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.cart.Cart;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.cart.CartItemRepository;
import com.project.dacn.repository.cart.CartRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.cart.CartService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two simultaneous checkouts must produce one order: the cart row lock makes
 * the loser wait, then see an empty cart.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({CartCheckoutTest.CheckoutMapperTestConfig.class, CartService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CartCheckoutConcurrencyTest {

    @Autowired
    CartService service;
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
    CartRepository cartRepository;
    @Autowired
    CartItemRepository cartItemRepository;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    OrderItemRepository orderItemRepository;
    @Autowired
    PaymentRepository paymentRepository;

    private Long customerId;

    @Test
    void concurrentCheckoutCreatesOneOrder() throws Exception {
        seedCart();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Object> attempt = () -> {
            start.await();
            try {
                return service.checkout(customerId,
                        new CartCheckoutRequest("addr", "N", "090", PaymentMethod.COD));
            } catch (IllegalArgumentException e) {
                return e;
            }
        };
        try {
            Future<Object> first = pool.submit(attempt);
            Future<Object> second = pool.submit(attempt);
            start.countDown();

            List<Object> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(OrderResponse.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(IllegalArgumentException.class::isInstance).hasSize(1);
            assertThat(orderRepository.count()).isEqualTo(1);
            assertThat(paymentRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private void seedCart() {
        Role role = roleRepository.save(Role.builder().code("co-race").name("R").build());
        User user = userRepository.save(User.builder()
                .email("co-race@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        Variant variant = variantRepository.save(Variant.builder().product(product)
                .sku("CO-RACE-1").price(new BigDecimal("10.00")).stock(5).build());
        Cart cart = cartRepository.save(Cart.builder().customer(customer).build());
        cartItemRepository.save(CartItem.builder()
                .cart(cart).variant(variant).quantity(2).build());
        customerId = customer.getId();
    }

    // This class commits rows to the shared in-memory database.
    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        variantRepository.deleteAll();
        productRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
    }
}
