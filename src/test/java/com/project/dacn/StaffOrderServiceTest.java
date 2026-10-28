package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.order.StaffOrderStatusUpdateRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffOrderServiceTest {

    @Mock
    OrderRepository orderRepository;

    @Mock
    OrderItemRepository orderItemRepository;

    @Mock
    VariantRepository variantRepository;

    @Mock
    PaymentRepository paymentRepository;

    OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);

    OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, orderItemRepository, orderMapper,
                variantRepository, paymentRepository);
    }

    @Test
    void staffListFiltersByStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        Order order = order(OrderStatus.PENDING);
        when(orderRepository.findByStatusOrderByIdDesc(OrderStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 1));
        when(orderItemRepository.findByOrder_OrderIdIn(List.of("ORD_1"))).thenReturn(List.of());

        PageResponse<OrderResponse> page = service.listForStaff(OrderStatus.PENDING, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement()
                .extracting(OrderResponse::status).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void staffListWithoutFilterReturnsAll() {
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findAllByOrderByIdDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(order(OrderStatus.CONFIRMED)), pageable, 1));
        when(orderItemRepository.findByOrder_OrderIdIn(List.of("ORD_1"))).thenReturn(List.of());

        assertThat(service.listForStaff(null, pageable).getContent()).hasSize(1);
    }

    @Test
    void acceptConfirmsPendingOrder() {
        when(orderRepository.findWithLockByOrderId("ORD_1"))
                .thenReturn(Optional.of(order(OrderStatus.PENDING)));
        when(orderItemRepository.findByOrder_OrderId("ORD_1")).thenReturn(List.of());

        OrderResponse response = service.updateStatusByStaff("ORD_1",
                new StaffOrderStatusUpdateRequest(OrderStatus.CONFIRMED));

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(variantRepository, never()).incrementStock(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(paymentRepository, never()).settle(anyString(), any(), any(), any(), any());
    }

    @Test
    void rejectCancelsRestocksAndFailsPendingPayment() {
        Order order = order(OrderStatus.PENDING);
        Variant first = variant(20L);
        Variant second = variant(21L);
        Payment payment = payment(order, PaymentStatus.PENDING);
        when(orderRepository.findWithLockByOrderId("ORD_1")).thenReturn(Optional.of(order));
        when(orderRepository.findByOrderId("ORD_1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrder_OrderId("ORD_1")).thenReturn(List.of(
                item(order, first, 2), item(order, second, 3)));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        OrderResponse response = service.updateStatusByStaff("ORD_1",
                new StaffOrderStatusUpdateRequest(OrderStatus.CANCELLED));

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(variantRepository).incrementStock(20L, 2);
        verify(variantRepository).incrementStock(21L, 3);
        verify(paymentRepository).settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.FAILED), eq(null), any(Instant.class));
    }

    @Test
    void rejectLeavesSettledPaymentAlone() {
        Order order = order(OrderStatus.PENDING);
        Payment payment = payment(order, PaymentStatus.CONFIRMED);
        when(orderRepository.findWithLockByOrderId("ORD_1")).thenReturn(Optional.of(order));
        when(orderRepository.findByOrderId("ORD_1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrder_OrderId("ORD_1")).thenReturn(List.of());
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        service.updateStatusByStaff("ORD_1",
                new StaffOrderStatusUpdateRequest(OrderStatus.CANCELLED));

        verify(paymentRepository, never()).settle(anyString(), any(), any(), any(), any());
    }

    @Test
    void rejectNonPendingOrderThrows409() {
        when(orderRepository.findWithLockByOrderId("ORD_1"))
                .thenReturn(Optional.of(order(OrderStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.updateStatusByStaff("ORD_1",
                new StaffOrderStatusUpdateRequest(OrderStatus.CANCELLED)))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("CONFIRMED");
        verify(variantRepository, never()).incrementStock(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void rejectMissingOrderThrows404() {
        when(orderRepository.findWithLockByOrderId("ORD_X")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatusByStaff("ORD_X",
                new StaffOrderStatusUpdateRequest(OrderStatus.CANCELLED)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void staffCannotSetOtherStatuses() {
        assertThatThrownBy(() -> service.updateStatusByStaff("ORD_1",
                new StaffOrderStatusUpdateRequest(OrderStatus.SHIPPING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFIRMED");
        verify(orderRepository, never()).findWithLockByOrderId(anyString());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "CONFIRMED", "SHIPPING"})
    void adminCanCompleteNonTerminalOrder(OrderStatus status) {
        Order order = order(status);
        when(orderRepository.findWithLockByOrderId("ORD_1"))
                .thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrder_OrderId("ORD_1")).thenReturn(List.of());

        OrderResponse response = service.completeByAdmin("ORD_1");

        assertThat(response.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(variantRepository, never()).incrementStock(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(paymentRepository, never()).settle(anyString(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CANCELLED", "COMPLETED"})
    void adminCannotCompleteTerminalOrder(OrderStatus status) {
        when(orderRepository.findWithLockByOrderId("ORD_1"))
                .thenReturn(Optional.of(order(status)));

        assertThatThrownBy(() -> service.completeByAdmin("ORD_1"))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void adminCompleteMissingOrderThrows404() {
        when(orderRepository.findWithLockByOrderId("ORD_X")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeByAdmin("ORD_X"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Order order(OrderStatus status) {
        return Order.builder()
                .id(1L)
                .orderId("ORD_1")
                .customer(Customer.builder().id(7L).customerId("cust").name("C").phone("090").build())
                .status(status)
                .total(new BigDecimal("150000.00"))
                .address("addr").recipientName("C").phone("090")
                .build();
    }

    private Variant variant(Long id) {
        return Variant.builder().id(id).variantId("VAR_" + id).sku("SKU_" + id)
                .price(new BigDecimal("10.00")).stock(5)
                .product(Product.builder().productId("p").name("P").description("d").build())
                .build();
    }

    private OrderItem item(Order order, Variant variant, int quantity) {
        return OrderItem.builder().order(order).variant(variant)
                .quantity(quantity).unitPrice(variant.getPrice()).build();
    }

    private Payment payment(Order order, PaymentStatus status) {
        return Payment.builder().paymentId("PAY_1").order(order)
                .method(PaymentMethod.COD).status(status)
                .amount(new BigDecimal("150000.00")).build();
    }
}
