package com.project.dacn.integration.shipping.ghn.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record GhnCreateOrderResponse(Integer code, String message, Data data) {
    public record Data(
            @JsonProperty("order_code") String orderCode,
            @JsonProperty("total_fee") BigDecimal totalFee) {
    }
}
