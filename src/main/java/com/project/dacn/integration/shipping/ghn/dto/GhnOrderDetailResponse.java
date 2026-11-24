package com.project.dacn.integration.shipping.ghn.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GhnOrderDetailResponse(Integer code, String message, Data data) {
    public record Data(
            @JsonProperty("order_code") String orderCode,
            String status) {
    }
}
