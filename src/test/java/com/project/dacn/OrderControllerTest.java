package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.controller.order.OrderController;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.security.JwtAuthenticationFilter;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

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

    private OrderResponse order() {
        return new OrderResponse("ORD_1", 1L, "Jane", OrderStatus.PENDING,
                new BigDecimal("8000.00"), "addr", "Jane", "090", List.of(), null, null);
    }

    @Test
    void listReturnsCustomerOrders() throws Exception {
        when(service.list(1L)).thenReturn(List.of(order()));

        mockMvc.perform(get("/api/v1/orders").header("X-Customer-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderId").value("ORD_1"))
                .andExpect(jsonPath("$.data[0].customerId").value(1));
    }

    @Test
    void getReturnsOrder() throws Exception {
        when(service.get(1L, "ORD_1")).thenReturn(order());

        mockMvc.perform(get("/api/v1/orders/ORD_1").header("X-Customer-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(8000.00));
    }

    @Test
    void missingCustomerHeaderIs400() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isBadRequest());
    }
}
