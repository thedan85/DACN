package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.dto.request.order.ShipOrderRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.shipping.ghn.GhnClient;
import com.project.dacn.integration.shipping.ghn.dto.GhnCreateOrderRequest;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.OrderService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@Import({StaffOrderPersistenceTest.MapperConfig.class, OrderService.class})
class StaffOrderShipTest {

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
    @Autowired
    EntityManager entityManager;

    Order order;
    OrderItem item;
    Payment payment;

    @BeforeEach
    void seed() {
        Role role = roleRepository.save(Role.builder().code("shipstaff").name("R").build());
        User user = userRepository.save(User.builder()
                .email("ship@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("Ship Customer").phone("0901234567").build());
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        Variant variant = variantRepository.save(Variant.builder().product(product)
                .sku("SHIP-SKU").price(new BigDecimal("1000.00")).stock(5).build());
        order = orderRepository.save(Order.builder()
                .customer(customer).status(OrderStatus.CONFIRMED).total(new BigDecimal("2000.00"))
                .address("123 Street").recipientName("Ship Customer").phone("0901234567")
                .shippingProvince("Hồ Chí Minh").shippingWard("Phường Chợ Quán").build());
        item = orderItemRepository.save(OrderItem.builder()
                .order(order).variant(variant).quantity(2)
                .unitPrice(new BigDecimal("1000.00")).build());
        payment = paymentRepository.save(Payment.builder()
                .order(order).method(PaymentMethod.COD).status(PaymentStatus.PENDING)
                .amount(new BigDecimal("2000.00")).build());
        entityManager.flush();
    }

    @Test
    void shipCreatesGhnOrderAndSavesCode() {
        when(ghnClient.createOrder(any())).thenReturn(new GhnClient.CreatedOrder("GHN-1", new BigDecimal("22000")));

        OrderResponse response = service.shipOrder(order.getOrderId(), null);

        assertThat(response.ghnOrderCode()).isEqualTo("GHN-1");
        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        ArgumentCaptor<GhnCreateOrderRequest> captor = ArgumentCaptor.forClass(GhnCreateOrderRequest.class);
        verify(ghnClient).createOrder(captor.capture());
        assertThat(captor.getValue().clientOrderCode()).isEqualTo(order.getOrderId());
        assertThat(captor.getValue().codAmount()).isEqualTo(2000);
        assertThat(captor.getValue().weight()).isEqualTo(1000);
    }

    @Test
    void nonConfirmedOrderIsRejected() {
        order.setStatus(OrderStatus.SHIPPING);
        orderRepository.saveAndFlush(order);

        assertThatThrownBy(() -> service.shipOrder(order.getOrderId(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("order is not confirmed: SHIPPING");
        verify(ghnClient, never()).createOrder(any());
    }

    @Test
    void unpaidNonCodOrderIsRejected() {
        payment.setMethod(PaymentMethod.CREDIT_CARD);
        payment.setStatus(PaymentStatus.PENDING);
        paymentRepository.saveAndFlush(payment);

        assertThatThrownBy(() -> service.shipOrder(order.getOrderId(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("not paid");
        verify(ghnClient, never()).createOrder(any());
    }

    @Test
    void missingAddressIsRejected() {
        order.setShippingProvince(null);
        order.setShippingWard(null);
        orderRepository.saveAndFlush(order);
        assertThatThrownBy(() -> service.shipOrder(order.getOrderId(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("no GHN address");
    }

    @Test
    void addressOverrideRepairsOldOrder() {
        order.setShippingProvince(null);
        order.setShippingWard(null);
        order.setAddress("Old street");
        orderRepository.saveAndFlush(order);
        when(ghnClient.createOrder(any()))
                .thenReturn(new GhnClient.CreatedOrder("GHN-1", new BigDecimal("22000")));
        OrderResponse response = service.shipOrder(order.getOrderId(),
                new ShipOrderRequest("123 New Street", "Hồ Chí Minh", "Phường Chợ Quán"));
        assertThat(response.address()).isEqualTo("123 New Street");
        assertThat(response.shippingProvince()).isEqualTo("Hồ Chí Minh");
        assertThat(response.shippingWard()).isEqualTo("Phường Chợ Quán");
        assertThat(response.ghnOrderCode()).isEqualTo("GHN-1");
    }

    @Test
    void duplicateGhnCodeIsRejected() {
        order.setGhnOrderCode("GHN-OLD");
        orderRepository.saveAndFlush(order);

        assertThatThrownBy(() -> service.shipOrder(order.getOrderId(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("order already has GHN order: GHN-OLD");
        verify(ghnClient, never()).createOrder(any());
    }

    @Test
    void heavyOrderIsRejected() {
        item.setQuantity(40);
        orderItemRepository.saveAndFlush(item);
        assertThatThrownBy(() -> service.shipOrder(order.getOrderId(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("20 kg");
        verify(ghnClient, never()).createOrder(any());
    }

    @Test
    void fractionalTotalIsRoundedToVnd() {
        order.setTotal(new BigDecimal("2000.50"));
        orderRepository.saveAndFlush(order);
        when(ghnClient.createOrder(any()))
                .thenReturn(new GhnClient.CreatedOrder("GHN-1", new BigDecimal("22000")));

        service.shipOrder(order.getOrderId(), null);

        ArgumentCaptor<GhnCreateOrderRequest> captor = ArgumentCaptor.forClass(GhnCreateOrderRequest.class);
        verify(ghnClient).createOrder(captor.capture());
        assertThat(captor.getValue().codAmount()).isEqualTo(2001);
    }

    @Test
    void retryReusesClientOrderCode() {
        when(ghnClient.createOrder(any()))
                .thenReturn(new GhnClient.CreatedOrder("GHN-1", new BigDecimal("22000")));
        service.shipOrder(order.getOrderId(), null);
        order.setGhnOrderCode(null);          // simulate a lost local save
        orderRepository.saveAndFlush(order);

        service.shipOrder(order.getOrderId(), null);

        ArgumentCaptor<GhnCreateOrderRequest> captor = ArgumentCaptor.forClass(GhnCreateOrderRequest.class);
        verify(ghnClient, times(2)).createOrder(captor.capture());
        assertThat(captor.getAllValues()).extracting(GhnCreateOrderRequest::clientOrderCode)
                .containsOnly(order.getOrderId());
    }
}
