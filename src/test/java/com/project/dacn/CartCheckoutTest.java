package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.OutOfStockException;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.cart.Cart;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.order.Payment;
import com.project.dacn.mapper.cart.CartMapper;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.cart.CartItemRepository;
import com.project.dacn.repository.cart.CartRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.order.PaymentRepository;
import com.project.dacn.service.cart.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartCheckoutTest {

    @TestConfiguration
    static class CheckoutMapperTestConfig {
        @Bean
        CartMapper cartMapper() {
            return Mappers.getMapper(CartMapper.class);
        }

        @Bean
        OrderMapper orderMapper() {
            return Mappers.getMapper(OrderMapper.class);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class CartCheckoutUnitTest {

        @Mock
        CustomerRepository customerRepository;

        @Mock
        VariantRepository variantRepository;

        @Mock
        CartRepository cartRepository;

        @Mock
        CartItemRepository cartItemRepository;

        @Mock
        OrderRepository orderRepository;

        @Mock
        OrderItemRepository orderItemRepository;

        @Mock
        PaymentRepository paymentRepository;

        CartMapper cartMapper = Mappers.getMapper(CartMapper.class);

        OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);

        CartService service;

        @BeforeEach
        void setUp() {
            service = new CartService(customerRepository, variantRepository,
                    cartRepository, cartItemRepository, cartMapper,
                    orderRepository, orderItemRepository, paymentRepository, orderMapper);
        }

        @Test
        void checkoutSnapshotsPriceAndClearsCart() {
            givenCart();
            Variant v = variant(10);
            CartItem existing = item(cart(customer()), v, 2);
            when(cartItemRepository.findWithDetailsByCart_CartId("cart-1"))
                    .thenReturn(List.of(existing))
                    .thenReturn(List.of());
            when(orderRepository.save(any(Order.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(orderItemRepository.saveAll(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any(Payment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            OrderResponse o = service.checkout("cust",
                    new CartCheckoutRequest("addr", "N", "090", PaymentMethod.COD));

            assertThat(o.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(o.total()).isEqualByComparingTo(new BigDecimal("20.00"));
            verify(cartItemRepository).deleteByCart_CartId("cart-1");
            verify(variantRepository, never()).save(any(Variant.class));
            assertThat(service.getDetail("cust").items()).isEmpty();
        }

        @Test
        void checkoutEmptyThrows400() {
            givenCart();
            when(cartItemRepository.findWithDetailsByCart_CartId("cart-1"))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.checkout("cust",
                    new CartCheckoutRequest("addr", "N", "090", PaymentMethod.COD)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(orderRepository, never()).save(any(Order.class));
        }

        @Test
        void checkoutOverStockThrows409() {
            givenCart();
            Variant v = variant(10);
            CartItem existing = item(cart(customer()), v, 99);
            when(cartItemRepository.findWithDetailsByCart_CartId("cart-1"))
                    .thenReturn(List.of(existing));

            assertThatThrownBy(() -> service.checkout("cust",
                    new CartCheckoutRequest("addr", "N", "090", PaymentMethod.COD)))
                    .isInstanceOf(OutOfStockException.class);
            verify(orderRepository, never()).save(any(Order.class));
            verify(cartItemRepository, never()).deleteByCart_CartId(any());
        }

        private void givenCart() {
            Customer c = customer();
            when(customerRepository.findByCustomerId("cust")).thenReturn(Optional.of(c));
            when(cartRepository.findByCustomer_CustomerId("cust"))
                    .thenReturn(Optional.of(cart(c)));
        }

        private Customer customer() {
            return Customer.builder().customerId("cust").name("C").phone("090").build();
        }

        private Cart cart(Customer c) {
            return Cart.builder().cartId("cart-1").customer(c).build();
        }

        private Variant variant(int stock) {
            Product product = Product.builder().productId("p").name("P")
                    .description("d").status(ProductStatus.ACTIVE).build();
            return Variant.builder().variantId("v").sku("SKU-1")
                    .price(new BigDecimal("10.00")).stock(stock).product(product).build();
        }

        private CartItem item(Cart cart, Variant variant, int qty) {
            return CartItem.builder().cartItemId("item-1")
                    .cart(cart).variant(variant).quantity(qty).build();
        }
    }

    @Nested
    @DataJpaTest
    @ActiveProfiles("test")
    @Import({CartService.class, CheckoutMapperTestConfig.class})
    class CartCheckoutPersistenceTest {

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

        @Test
        void checkoutPersistsPendingOrderAndClearsCart() {
            Role role = roleRepository.save(Role.builder().code("co1").name("R").build());
            User user = userRepository.save(User.builder()
                    .email("co@x.com").passwordHash("h").role(role).build());
            Customer customer = customerRepository.save(Customer.builder()
                    .user(user).name("C").phone("090").build());
            Product product = productRepository.save(Product.builder().name("P").description("d").build());
            Variant variant = variantRepository.save(Variant.builder().product(product)
                    .sku("CO-SKU-1").price(new BigDecimal("10.00")).stock(5).build());
            Cart cart = cartRepository.save(Cart.builder().customer(customer).build());
            cartItemRepository.save(CartItem.builder()
                    .cart(cart).variant(variant).quantity(2).build());

            OrderResponse o = service.checkout(customer.getCustomerId(),
                    new CartCheckoutRequest("addr", "N", "090", PaymentMethod.COD));

            assertThat(o.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(o.total()).isEqualByComparingTo(new BigDecimal("20.00"));
            assertThat(orderRepository.findAll()).hasSize(1);
            assertThat(orderRepository.findAll().get(0).getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(orderItemRepository.findByOrder_OrderId(o.orderId())).hasSize(1);
            assertThat(orderItemRepository.findByOrder_OrderId(o.orderId()).get(0).getUnitPrice())
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(paymentRepository.findByOrder_OrderId(o.orderId())).isPresent();
            assertThat(paymentRepository.findByOrder_OrderId(o.orderId()).get().getStatus())
                    .isEqualTo(PaymentStatus.PENDING);
            assertThat(paymentRepository.findByOrder_OrderId(o.orderId()).get().getAmount())
                    .isEqualByComparingTo(new BigDecimal("20.00"));
            assertThat(service.getDetail(customer.getCustomerId()).items()).isEmpty();
            assertThat(variantRepository.findById(variant.getId()).get().getStock()).isEqualTo(5);
        }
    }
}
