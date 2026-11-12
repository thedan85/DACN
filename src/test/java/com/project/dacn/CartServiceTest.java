package com.project.dacn;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.OutOfStockException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.dto.request.cart.CartItemAddRequest;
import com.project.dacn.dto.request.cart.CartItemUpdateRequest;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.cart.Cart;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.mapper.cart.CartMapper;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
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
import com.project.dacn.service.order.OrderLifecycleService;
import com.project.dacn.service.order.ReservationPolicy;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartServiceTest {

    @TestConfiguration
    static class MapperTestConfig {
        @Bean
        CartMapper cartMapper() {
            return Mappers.getMapper(CartMapper.class);
        }

        @Bean
        OrderMapper orderMapper() {
            return Mappers.getMapper(OrderMapper.class);
        }

        @Bean
        PaymentMapper paymentMapper() {
            return Mappers.getMapper(PaymentMapper.class);
        }

        @Bean
        ReservationPolicy reservationPolicy() {
            return new ReservationPolicy(false, false, false);
        }

        @Bean
        DatabaseClock databaseClock(DataSource source) {
            return new DatabaseClock(new JdbcTemplate(source));
        }

        @Bean
        OrderLifecycleService orderLifecycleService() {
            return mock(OrderLifecycleService.class);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class CartServiceUnitTest {

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

        @Mock
        OrderLifecycleService orderLifecycleService;

        @Mock
        DatabaseClock databaseClock;

        CartMapper mapper = Mappers.getMapper(CartMapper.class);

        OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);

        PaymentMapper paymentMapper = Mappers.getMapper(PaymentMapper.class);

        CartService service;

        @BeforeEach
        void setUp() {
            service = new CartService(customerRepository, variantRepository,
                    cartRepository, cartItemRepository, mapper,
                    orderRepository, orderItemRepository, paymentRepository, orderMapper,
                    paymentMapper, new ReservationPolicy(false, false, false),
                    orderLifecycleService, databaseClock);
            ReflectionTestUtils.setField(service, "transactionLockBounds", Boolean.FALSE);
        }

        @Test
        void addBumpsExistingQty() {
            givenCart();
            Variant v = variant(10);
            when(variantRepository.findWithProductByVariantId("VAR_20")).thenReturn(Optional.of(v));
            CartItem existing = item(cart(customer()), v, 2);
            when(cartItemRepository.findByCart_IdAndVariant_Id(10L, 20L))
                    .thenReturn(Optional.of(existing));
            when(cartItemRepository.save(any(CartItem.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            CartItemResponse result = service.addItem(1L, new CartItemAddRequest("VAR_20", 3));

            assertThat(result.quantity()).isEqualTo(5);
            assertThat(result.cartItemId()).isEqualTo("item-1");
            assertThat(result.variantId()).isEqualTo("VAR_20");
        }

        @Test
        void patchOverStockThrows409() {
            givenCart();
            Variant v = variant(10);
            CartItem existing = item(cart(customer()), v, 2);
            when(cartItemRepository.findByCartItemId("item-1")).thenReturn(Optional.of(existing));
            when(variantRepository.findWithProductByVariantId("VAR_20")).thenReturn(Optional.of(v));

            assertThatThrownBy(() -> service.setQuantity(1L, "item-1", new CartItemUpdateRequest(99)))
                    .isInstanceOf(OutOfStockException.class);
        }

        @Test
        void patchOnDeactivatedProductThrows404() {
            givenCart();
            Product product = Product.builder().productId("p").name("P")
                    .description("d").status(ProductStatus.INACTIVE).build();
            Variant stale = Variant.builder().id(20L).variantId("VAR_20").sku("SKU-1")
                    .price(new BigDecimal("10.00")).stock(10).product(product).build();
            CartItem existing = item(cart(customer()), stale, 2);
            when(cartItemRepository.findByCartItemId("item-1")).thenReturn(Optional.of(existing));
            when(variantRepository.findWithProductByVariantId("VAR_20")).thenReturn(Optional.of(stale));

            assertThatThrownBy(() -> service.setQuantity(1L, "item-1", new CartItemUpdateRequest(1)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void detailSumsTotals() {
            Customer c = customer();
            when(cartRepository.findByCustomer_IdForUpdate(1L))
                    .thenReturn(Optional.of(cart(c)));
            Variant v = variant(10);
            CartItem existing = item(cart(customer()), v, 2);
            when(cartItemRepository.findWithDetailsByCart_Id(10L))
                    .thenReturn(List.of(existing));

            CartDetailResponse detail = service.getDetail(1L);

            assertThat(detail.customerId()).isEqualTo("cust");
            assertThat(detail.totalQuantity()).isEqualTo(2);
            assertThat(detail.totalAmount()).isEqualByComparingTo(new BigDecimal("20.00"));
        }

        @Test
        void otherCustomersItemThrows404() {
            givenCart();
            Cart otherCart = Cart.builder().id(20L).cartId("cart-2").customer(customer()).build();
            CartItem foreign = CartItem.builder().id(999L).cartItemId("item-9")
                    .cart(otherCart).variant(variant(10)).quantity(1).build();
            when(cartItemRepository.findByCartItemId("item-9")).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> service.removeItem(1L, "item-9"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void softDeletedVariantThrows404() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer()));
            Variant v = variant(10);
            v.setDeleted(true);
            when(variantRepository.findWithProductByVariantId("VAR_20")).thenReturn(Optional.of(v));

            assertThatThrownBy(() -> service.addItem(1L, new CartItemAddRequest("VAR_20", 1)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void uniquenessFailureIsNotRetried() {
            givenCart();
            Variant v = variant(10);
            when(variantRepository.findWithProductByVariantId("VAR_20")).thenReturn(Optional.of(v));
            when(cartItemRepository.findByCart_IdAndVariant_Id(10L, 20L))
                    .thenReturn(Optional.empty());
            when(cartItemRepository.save(any(CartItem.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_cart_variant"));

            assertThatThrownBy(() -> service.addItem(1L, new CartItemAddRequest("VAR_20", 3)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            verify(cartItemRepository).findByCart_IdAndVariant_Id(10L, 20L);
            verify(cartItemRepository).save(any(CartItem.class));
        }

        @Test
        void invalidVariantCreatesNoCart() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer()));
            when(variantRepository.findWithProductByVariantId("VAR_999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addItem(1L, new CartItemAddRequest("VAR_999", 1)))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(cartRepository, never()).save(any(Cart.class));
        }

        @Test
        void crossCartRemoveCreatesNoCart() {
            givenCart();
            Cart otherCart = Cart.builder().id(20L).cartId("cart-2").customer(customer()).build();
            CartItem foreign = CartItem.builder().id(999L).cartItemId("item-9")
                    .cart(otherCart).variant(variant(10)).quantity(1).build();
            when(cartItemRepository.findByCartItemId("item-9")).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> service.removeItem(1L, "item-9"))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(cartRepository, never()).save(any(Cart.class));
        }

        private void givenCart() {
            Customer c = customer();
            when(customerRepository.findById(1L)).thenReturn(Optional.of(c));
            when(cartRepository.findByCustomer_IdForUpdate(1L))
                    .thenReturn(Optional.of(cart(c)));
        }

        private Customer customer() {
            return Customer.builder().id(1L).customerId("cust").name("C").phone("090").build();
        }

        private Cart cart(Customer c) {
            return Cart.builder().id(10L).cartId("cart-1").customer(c).build();
        }

        private Variant variant(int stock) {
            Product product = Product.builder().productId("p").name("P")
                    .description("d").status(ProductStatus.ACTIVE).build();
            return Variant.builder().id(20L).variantId("VAR_20").sku("SKU-1")
                    .price(new BigDecimal("10.00")).stock(stock).product(product).build();
        }

        private CartItem item(Cart cart, Variant variant, int qty) {
            return CartItem.builder().id(100L).cartItemId("item-1")
                    .cart(cart).variant(variant).quantity(qty).build();
        }
    }

    @Nested
    @DataJpaTest
    @ActiveProfiles("test")
    @Import({ CartService.class, MapperTestConfig.class })
    class CartServiceTotalsPersistenceTest {

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

        @Test
        void persistedCartDetailSumsTotals() {
            Role role = roleRepository.save(Role.builder().code("csr1").name("R").build());
            User user = userRepository.save(User.builder()
                    .email("svc@x.com").passwordHash("h").role(role).build());
            Customer customer = customerRepository.save(Customer.builder()
                    .user(user).name("C").phone("090").build());
            Product product = productRepository.save(Product.builder().name("P").description("d").build());
            Variant variant = variantRepository.save(Variant.builder().product(product)
                    .sku("SVC-SKU-1").price(new BigDecimal("10.00")).stock(5).build());
            Cart cart = cartRepository.save(Cart.builder().customer(customer).build());
            CartItem item = cartItemRepository.save(CartItem.builder()
                    .cart(cart).variant(variant).quantity(2).build());

            service.setQuantity(customer.getId(), item.getCartItemId(), new CartItemUpdateRequest(3));

            CartDetailResponse detail = service.getDetail(customer.getId());

            assertThat(detail.customerId()).isEqualTo(customer.getCustomerId());
            assertThat(detail.items()).hasSize(1);
            assertThat(detail.items().get(0).cartItemId()).isEqualTo(item.getCartItemId());
            assertThat(detail.items().get(0).variantId()).isEqualTo(variant.getVariantId());
            assertThat(detail.totalQuantity()).isEqualTo(3);
            assertThat(detail.totalAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        }
    }
}
