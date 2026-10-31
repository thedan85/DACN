package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.controller.order.AdminOrderController;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminOrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService service;

    // JwtAuthenticationFilter is a Filter, so the slice instantiates it even with
    // addFilters = false; these mocks satisfy its constructor.
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void completesOrder() throws Exception {
        when(service.completeByAdmin("ORD_1")).thenReturn(order(OrderStatus.COMPLETED));

        mockMvc.perform(post("/api/v1/admin/orders/ORD_1/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("ORD_1"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(service).completeByAdmin("ORD_1");
    }

    private OrderResponse order(OrderStatus status) {
        return new OrderResponse("ORD_1", "CUS_1", "Jane", status,
                new BigDecimal("8000.00"), "addr", "Jane", "090", List.of(), null, null, null);
    }
}
