package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.controller.order.StaffOrderController;
import com.project.dacn.dto.request.order.StaffOrderStatusUpdateRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.integration.shipping.ShippingException;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffOrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class StaffOrderControllerTest {

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
    void listReturnsFilteredOrders() throws Exception {
        when(service.listForStaff(OrderStatus.PENDING, null, PageRequest.of(0, 20)))
                .thenReturn(new PageResponse<>(List.of(order(OrderStatus.PENDING)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/staff/orders")
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].orderId").value("ORD_1"))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"));
    }

    @Test
    void listPassesSearchTerm() throws Exception {
        when(service.listForStaff(null, "swagger", PageRequest.of(0, 20)))
                .thenReturn(new PageResponse<>(List.of(order(OrderStatus.PENDING)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/staff/orders")
                        .param("q", "swagger")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk());

        verify(service).listForStaff(null, "swagger", PageRequest.of(0, 20));
    }

    @Test
    void patchUpdatesOrderStatus() throws Exception {
        when(service.updateStatusByStaff("ORD_1",
                new StaffOrderStatusUpdateRequest(OrderStatus.CONFIRMED)))
                .thenReturn(order(OrderStatus.CONFIRMED));

        mockMvc.perform(patch("/api/v1/staff/orders/ORD_1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        verify(service).updateStatusByStaff("ORD_1",
                new StaffOrderStatusUpdateRequest(OrderStatus.CONFIRMED));
    }

    @Test
    void shipReturnsUpdatedOrder() throws Exception {
        when(service.shipOrder("ORD_1", null)).thenReturn(order(OrderStatus.CONFIRMED));
        mockMvc.perform(post("/api/v1/staff/orders/ORD_1/ship"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("ORD_1"));
    }

    @Test
    void shipReturns502WhenGhnFails() throws Exception {
        when(service.shipOrder("ORD_1", null)).thenThrow(new ShippingException("GHN: bad ward"));
        mockMvc.perform(post("/api/v1/staff/orders/ORD_1/ship"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("GHN: bad ward"));
    }

    private OrderResponse order(OrderStatus status) {
        return new OrderResponse("ORD_1", "CUS_1", "Jane", status,
                new BigDecimal("8000.00"), "addr", "Jane", "090", null, null, null, List.of(),
                null, null, null);
    }
}
