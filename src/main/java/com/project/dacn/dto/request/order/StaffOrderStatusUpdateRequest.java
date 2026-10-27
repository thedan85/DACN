package com.project.dacn.dto.request.order;

import com.project.dacn.common.constant.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record StaffOrderStatusUpdateRequest(
        @NotNull
        @Schema(example = "CONFIRMED")
        OrderStatus status) {
}
