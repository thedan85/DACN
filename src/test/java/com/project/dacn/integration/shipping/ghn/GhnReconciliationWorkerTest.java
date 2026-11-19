package com.project.dacn.integration.shipping.ghn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.entity.order.Order;
import com.project.dacn.integration.shipping.ShippingException;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;

import static com.project.dacn.integration.shipping.ghn.GhnStatusMapper.Event.DELIVERED;
import static com.project.dacn.integration.shipping.ghn.GhnStatusMapper.Event.SHIPPING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GhnReconciliationWorkerTest {

    @Mock
    GhnClient ghnClient;

    @Mock
    OrderRepository orderRepository;

    @Mock
    OrderService orderService;

    GhnReconciliationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new GhnReconciliationWorker(ghnClient, orderRepository, orderService);
    }

    @Test
    void mapsPickedAndDelivered() {
        when(ghnClient.isConfigured()).thenReturn(true);
        when(orderRepository.findGhnReconciliationCandidates(any(), anyLong(), any()))
                .thenReturn(List.of(order(1L, "GHN-1", "ORD_1"), order(2L, "GHN-2", "ORD_2")));
        when(ghnClient.getOrderStatus("GHN-1")).thenReturn("picked");
        when(ghnClient.getOrderStatus("GHN-2")).thenReturn("delivered");

        worker.reconcile();

        verify(orderService).applyGhnEvent("GHN-1", "ORD_1", SHIPPING);
        verify(orderService).applyGhnEvent("GHN-2", "ORD_2", DELIVERED);
    }

    @Test
    void ignoresReadyToPickAndUnknownStatus() {
        when(ghnClient.isConfigured()).thenReturn(true);
        when(orderRepository.findGhnReconciliationCandidates(any(), anyLong(), any()))
                .thenReturn(List.of(order(1L, "GHN-1", "ORD_1"), order(2L, "GHN-2", "ORD_2")));
        when(ghnClient.getOrderStatus("GHN-1")).thenReturn("ready_to_pick");
        when(ghnClient.getOrderStatus("GHN-2")).thenReturn("unknown_status");

        worker.reconcile();

        verifyNoInteractions(orderService);
    }

    @Test
    void isolatesOneFailureAndContinues() {
        when(ghnClient.isConfigured()).thenReturn(true);
        when(orderRepository.findGhnReconciliationCandidates(any(), anyLong(), any()))
                .thenReturn(List.of(order(1L, "GHN-1", "ORD_1"), order(2L, "GHN-2", "ORD_2")));
        when(ghnClient.getOrderStatus("GHN-1")).thenThrow(new ShippingException("GHN: boom"));
        when(ghnClient.getOrderStatus("GHN-2")).thenReturn("delivered");

        worker.reconcile();

        verify(orderService).applyGhnEvent("GHN-2", "ORD_2", DELIVERED);
        verifyNoMoreInteractions(orderService);
    }

    @Test
    void rotatesCursorAcrossTicksAndResetsAfterShortBatch() {
        ReflectionTestUtils.setField(worker, "batchSize", 2);
        when(ghnClient.isConfigured()).thenReturn(true);
        when(orderRepository.findGhnReconciliationCandidates(any(), anyLong(), any()))
                .thenReturn(List.of(order(1L, "GHN-1", "ORD_1"), order(2L, "GHN-2", "ORD_2")),
                        List.of(order(3L, "GHN-3", "ORD_3"), order(4L, "GHN-4", "ORD_4")),
                        List.of(order(5L, "GHN-5", "ORD_5")),
                        List.of(order(6L, "GHN-6", "ORD_6")));
        when(ghnClient.getOrderStatus(anyString())).thenReturn("ready_to_pick");

        worker.reconcile();
        worker.reconcile();
        worker.reconcile();
        worker.reconcile();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Collection> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Long> afterId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository, times(4))
                .findGhnReconciliationCandidates(statuses.capture(), afterId.capture(), page.capture());
        assertThat(statuses.getAllValues()).allSatisfy(set ->
                assertThat(set).containsExactly(OrderStatus.CONFIRMED, OrderStatus.SHIPPING));
        assertThat(afterId.getAllValues()).containsExactly(0L, 2L, 4L, 0L);
        assertThat(page.getAllValues()).allSatisfy(p -> assertThat(p.getPageSize()).isEqualTo(2));
        verifyNoInteractions(orderService);
    }

    @Test
    void skipsWhenGhnIsUnconfigured() {
        when(ghnClient.isConfigured()).thenReturn(false);

        worker.reconcile();

        verifyNoInteractions(orderRepository, orderService);
        verifyNoMoreInteractions(ghnClient);
    }

    @Test
    void selectsNonTerminalStatusesWithBoundedPage() {
        ReflectionTestUtils.setField(worker, "batchSize", 7);
        when(ghnClient.isConfigured()).thenReturn(true);
        when(orderRepository.findGhnReconciliationCandidates(any(), anyLong(), any())).thenReturn(List.of());

        worker.reconcile();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Collection> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Long> afterId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findGhnReconciliationCandidates(statuses.capture(), afterId.capture(), page.capture());
        assertThat(statuses.getValue()).containsExactly(OrderStatus.CONFIRMED, OrderStatus.SHIPPING);
        assertThat(afterId.getValue()).isZero();
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(7);
    }

    @Test
    void capsOversizedBatch() {
        ReflectionTestUtils.setField(worker, "batchSize", 999);
        when(ghnClient.isConfigured()).thenReturn(true);
        when(orderRepository.findGhnReconciliationCandidates(any(), anyLong(), any())).thenReturn(List.of());

        worker.reconcile();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findGhnReconciliationCandidates(any(), anyLong(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(500);
    }

    private Order order(Long id, String ghnCode, String orderId) {
        Order order = Order.builder().id(id).status(OrderStatus.CONFIRMED).build();
        order.setOrderId(orderId);
        order.setGhnOrderCode(ghnCode);
        return order;
    }
}
