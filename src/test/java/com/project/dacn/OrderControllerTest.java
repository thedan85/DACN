package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.controller.order.OrderController;
import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.security.CustomerIdentity;
import com.project.dacn.security.JwtAuthenticationFilter;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService service;

    @MockitoBean
    private CustomerIdentity identity;

    private OrderResponse order() {
        return new OrderResponse("ORD_1", "CUS_1", "Jane", OrderStatus.PENDING,
                new BigDecimal("8000.00"), "addr", "Jane", "090", List.of(), null, null, null);
    }

    private OrderResponse orderWithItem() {
        OrderItemResponse item = new OrderItemResponse("ORD_1", "VAR_1", "SKU-1", 1,
                new BigDecimal("8000.00"), null, null, null, List.of());
        return new OrderResponse("ORD_1", "CUS_1", "Jane", OrderStatus.PENDING,
                new BigDecimal("8000.00"), "addr", "Jane", "090", List.of(item), null, null,
                null);
    }

    @Test
    void listReturnsCustomerOrders() throws Exception {
        when(identity.requireCustomerId(any())).thenReturn(1L);
        when(service.list(eq(1L), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(order()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].orderId").value("ORD_1"))
                .andExpect(jsonPath("$.data.content[0].customerId").value("CUS_1"));
    }

    @Test
    void getReturnsOrder() throws Exception {
        when(identity.requireCustomerId(any())).thenReturn(1L);
        when(service.get(1L, "ORD_1")).thenReturn(order());

        mockMvc.perform(get("/api/v1/orders/ORD_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(8000.00));
    }

    @Test
    void orderExposesBusinessIdsOnly() throws Exception {
        when(identity.requireCustomerId(any())).thenReturn(1L);
        when(service.get(1L, "ORD_1")).thenReturn(orderWithItem());

        mockMvc.perform(get("/api/v1/orders/ORD_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value("CUS_1"))
                .andExpect(jsonPath("$.data.items[0].orderItemId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].variantId").value("VAR_1"));
    }
}
