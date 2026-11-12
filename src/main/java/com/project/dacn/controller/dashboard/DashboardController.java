package com.project.dacn.controller.dashboard;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.response.dashboard.DashboardSummaryResponse;
import com.project.dacn.security.Permissions;
import com.project.dacn.service.dashboard.DashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@Tag(name = "admin-dashboard")
public class DashboardController {

    private final DashboardService service;

    /**
     * Store overview for the last 7, 30 or 90 days. Revenue excludes cancelled
     * orders and the low-stock list uses a fixed total-variant threshold.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('" + Permissions.ORDER_READ + "')")
    public ApiResponse<DashboardSummaryResponse> summary(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(service.summary(days));
    }
}
