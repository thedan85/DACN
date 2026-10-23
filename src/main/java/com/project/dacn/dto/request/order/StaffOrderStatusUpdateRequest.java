package com.project.dacn.dto.request.order;

import com.project.dacn.common.constant.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record StaffOrderStatusUpdateRequest(@NotNull OrderStatus status) {
}
