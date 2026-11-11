package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.shipping.ghn.GhnClient;
import com.project.dacn.integration.shipping.ghn.GhnStatusMapper;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.project.dacn.integration.shipping.ghn.GhnStatusMapper.Event.CANCELLED;
import static com.project.dacn.integration.shipping.ghn.GhnStatusMapper.Event.DELIVERED;
import static com.project.dacn.integration.shipping.ghn.GhnStatusMapper.Event.RETURNED;
import static com.project.dacn.integration.shipping.ghn.GhnStatusMapper.Event.SHIPPING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceGhnStatusTest {

    @Mock
    OrderRepository orderRepository;

    @Mock
    OrderItemRepository orderItemRepository;

    @Mock
    VariantRepository variantRepository;

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    VariantAttributeValueRepository variantAttributeValueRepository;

    @Mock
    MediaRepository mediaRepository;

    @Mock
    GhnClient ghnClient;

    OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);

    PaymentMapper paymentMapper = Mappers.getMapper(PaymentMapper.class);

    OrderService service;

    Order order;
    OrderItem item;
    Payment payment;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, orderItemRepository, orderMapper,
                paymentMapper, variantRepository, variantAttributeValueRepository,
                mediaRepository, paymentRepository, ghnClient);

        order = Order.builder()
                .id(1L)
                .customer(Customer.builder().id(7L).customerId("cust").name("C").phone("090").build())
                .status(OrderStatus.CONFIRMED)
                .total(new BigDecimal("2000.00"))
                .address("addr").recipientName("C").phone("090")
                .build();
        order.setOrderId("ORD_1");
        order.setGhnOrderCode("GHN-1");

        Variant variant = Variant.builder()
                .variantId("VAR_20").sku("SKU_20")
                .price(new BigDecimal("1000.00")).stock(5)
                .product(Product.builder().productId("p").name("P").description("d").build())
                .build();
        variant.setId(20L);
        item = OrderItem.builder().order(order).variant(variant)
                .quantity(2).unitPrice(new BigDecimal("1000.00")).build();

        payment = Payment.builder().order(order)
                .method(PaymentMethod.COD).status(PaymentStatus.PENDING)
                .amount(new BigDecimal("2000.00"))
                .build();
        payment.setPaymentId("PAY_1");
    }

    @Test
    void pickedMovesConfirmedToShipping() {
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));
        service.applyGhnEvent("GHN-1", null, SHIPPING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    void deliveredMovesConfirmedToCompleted() {
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));
        service.applyGhnEvent("GHN-1", null, DELIVERED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void cancelRestocksAndFailsPaymentOnce() {
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrder_OrderId("ORD_1")).thenReturn(List.of(item));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        service.applyGhnEvent("GHN-1", null, CANCELLED);
        service.applyGhnEvent("GHN-1", null, CANCELLED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(variantRepository, times(1)).incrementStock(20L, 2);
        verify(paymentRepository, times(1)).settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.FAILED), eq(null), any(Instant.class));
    }

    @Test
    void returnedFromCompletedCancelsAndRestocks() {
        order.setStatus(OrderStatus.COMPLETED);
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrder_OrderId("ORD_1")).thenReturn(List.of(item));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        service.applyGhnEvent("GHN-1", null, RETURNED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(variantRepository).incrementStock(20L, 2);
        verify(paymentRepository).settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.FAILED), eq(null), any(Instant.class));
    }

    @Test
    void cancelCannotRegressCompleted() {
        order.setStatus(OrderStatus.COMPLETED);
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.of(order));

        service.applyGhnEvent("GHN-1", null, CANCELLED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(variantRepository, never()).incrementStock(any(), anyInt());
        verify(paymentRepository, never()).settle(anyString(), any(), any(), any(), any());
    }

    @Test
    void unpaidNonCodManualOrderIsNotAdvanced() {
        payment.setMethod(PaymentMethod.CREDIT_CARD);
        payment.setStatus(PaymentStatus.PENDING);
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        service.applyGhnEvent("GHN-1", null, SHIPPING);
        service.applyGhnEvent("GHN-1", null, DELIVERED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(variantRepository, never()).incrementStock(any(), anyInt());
    }

    @Test
    void pendingIsNotAdvanced() {
        order.setStatus(OrderStatus.PENDING);
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.of(order));

        service.applyGhnEvent("GHN-1", null, SHIPPING);
        service.applyGhnEvent("GHN-1", null, DELIVERED);
        service.applyGhnEvent("GHN-1", null, RETURNED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(variantRepository, never()).incrementStock(any(), anyInt());
    }

    @Test
    void clientCodeFallbackSetsGhnCode() {
        order.setGhnOrderCode(null);
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.empty());
        when(orderRepository.findWithLockByOrderId("ORD_1")).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));
        service.applyGhnEvent("GHN-1", "ORD_1", SHIPPING);
        assertThat(order.getGhnOrderCode()).isEqualTo("GHN-1");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    void differentClientCodeIsIgnored() {
        order.setGhnOrderCode("OTHER");
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.empty());
        when(orderRepository.findWithLockByOrderId("ORD_1")).thenReturn(Optional.of(order));
        service.applyGhnEvent("GHN-1", "ORD_1", SHIPPING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void unknownGhnOrderIsIgnored() {
        when(orderRepository.findWithLockByGhnOrderCode("GHN-X")).thenReturn(Optional.empty());
        service.applyGhnEvent("GHN-X", "ORD_X", SHIPPING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verifyNoInteractions(paymentRepository, variantRepository);
    }

    @Test
    void duplicateDeliveredIsNoOp() {
        order.setStatus(OrderStatus.COMPLETED);
        when(orderRepository.findWithLockByGhnOrderCode("GHN-1")).thenReturn(Optional.of(order));
        service.applyGhnEvent("GHN-1", null, DELIVERED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verifyNoInteractions(paymentRepository, variantRepository);
    }
}
