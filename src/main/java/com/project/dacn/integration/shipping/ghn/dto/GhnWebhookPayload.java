package com.project.dacn.integration.shipping.ghn.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GhnWebhookPayload(
        @JsonProperty("ShopID") Integer shopId,
        @JsonProperty("OrderCode") String orderCode,
        @JsonProperty("ClientOrderCode") String clientOrderCode,
        @JsonProperty("Type") String type,
        @JsonProperty("Status") String status,
        @JsonProperty("Reason") String reason,
        @JsonProperty("ReasonCode") String reasonCode) {
}
