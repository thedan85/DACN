package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.integration.shipping.ghn.GhnClient;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerOrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock OrderMapper orderMapper;
    @Mock PaymentMapper paymentMapper;
    @Mock VariantRepository variantRepository;
    @Mock VariantAttributeValueRepository variantAttributeValueRepository;
    @Mock MediaRepository mediaRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock GhnClient ghnClient;
    @InjectMocks OrderService service;

    @Test
    void trimsOrderIdSearchAndDelegatesFilters() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        Pageable pageable = PageRequest.of(2, 10);
        String query = "x".repeat(100);
        when(orderRepository.searchForCustomer(7L, query, OrderStatus.CONFIRMED,
                cutoff, pageable)).thenReturn(Page.empty(pageable));

        PageResponse<OrderResponse> response = service.list(
                7L, OrderStatus.CONFIRMED, "  " + query + "  ", cutoff, pageable);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getPage()).isEqualTo(2);
        assertThat(response.getSize()).isEqualTo(10);
        verify(orderRepository).searchForCustomer(7L, query, OrderStatus.CONFIRMED,
                cutoff, pageable);
    }

    @Test
    void rejectsTrimmedOrderIdSearchLongerThan100Characters() {
        String tooLong = "  " + "x".repeat(101) + "  ";

        assertThatThrownBy(() -> service.list(7L, null, tooLong, null, PageRequest.of(0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
        verifyNoInteractions(orderRepository);
    }

    @Test
    void absentSearchUsesEmptyQuery() {
        when(orderRepository.searchForCustomer(eq(7L), eq(""), eq(null), eq(null),
                any(Pageable.class))).thenReturn(Page.empty());

        assertThat(service.list(7L, null, null, null, PageRequest.of(0, 10))
                .getContent()).isEmpty();
        verify(orderRepository).searchForCustomer(eq(7L), eq(""), eq(null), eq(null),
                any(Pageable.class));
    }
}
