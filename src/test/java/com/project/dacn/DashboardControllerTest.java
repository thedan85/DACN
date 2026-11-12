package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.controller.dashboard.DashboardController;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.CompletionRate;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.CountKpi;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.Granularity;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.LowStockProduct;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.LowStockSummary;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.MoneyKpi;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.OrderStatusCount;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse.RevenuePoint;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import com.project.dacn.service.dashboard.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService service;

    // JwtAuthenticationFilter is a Filter, so the slice instantiates it even with
    // addFilters = false; these mocks satisfy its constructor.
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void defaultsToThirtyDays() throws Exception {
        when(service.summary(30)).thenReturn(summary());

        mockMvc.perform(get("/api/v1/admin/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.granularity").value("DAY"))
                .andExpect(jsonPath("$.data.revenue.value").value(1500000))
                .andExpect(jsonPath("$.data.revenue.changePct").value(12.5))
                .andExpect(jsonPath("$.data.orders.value").value(8))
                .andExpect(jsonPath("$.data.activeProducts").value(42))
                .andExpect(jsonPath("$.data.completionRate.pct").value(62.5))
                .andExpect(jsonPath("$.data.revenueSeries[0].date").value("2026-10-13"))
                .andExpect(jsonPath("$.data.orderStatus[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.lowStockProducts[0].stock").value(3));

        verify(service).summary(30);
    }

    @Test
    void passesRequestedRange() throws Exception {
        when(service.summary(7)).thenReturn(summary());

        mockMvc.perform(get("/api/v1/admin/dashboard/summary").param("days", "7"))
                .andExpect(status().isOk());

        verify(service).summary(7);
    }

    @Test
    void rejectsUnsupportedRange() throws Exception {
        when(service.summary(14)).thenThrow(new IllegalArgumentException("days must be 7, 30, or 90"));

        mockMvc.perform(get("/api/v1/admin/dashboard/summary").param("days", "14"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("days must be 7, 30, or 90"));
    }

    private DashboardSummaryResponse summary() {
        return new DashboardSummaryResponse(
                Instant.parse("2026-11-11T03:00:00Z"),
                LocalDate.parse("2026-10-13"),
                LocalDate.parse("2026-11-11"),
                Granularity.DAY,
                new MoneyKpi(new BigDecimal("1500000.00"), new BigDecimal("1333333.33"),
                        new BigDecimal("12.5")),
                new CountKpi(8, 6, new BigDecimal("33.3")),
                42,
                new LowStockSummary(10, 2),
                List.of(new RevenuePoint(LocalDate.parse("2026-10-13"),
                        new BigDecimal("0.00"), 0)),
                List.of(new OrderStatusCount(OrderStatus.PENDING, 3)),
                new CompletionRate(5, 8, new BigDecimal("62.5")),
                List.of(new LowStockProduct("PRD_1", "p1", "P1", null, 3)));
    }
}
