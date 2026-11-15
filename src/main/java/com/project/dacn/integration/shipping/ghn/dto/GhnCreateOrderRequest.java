package com.project.dacn.integration.shipping.ghn.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GhnCreateOrderRequest(
        @JsonProperty("payment_type_id") int paymentTypeId,
        @JsonProperty("required_note") String requiredNote,
        @JsonProperty("client_order_code") String clientOrderCode,
        @JsonProperty("to_name") String toName,
        @JsonProperty("to_phone") String toPhone,
        @JsonProperty("to_address") String toAddress,
        @JsonProperty("to_ward_name") String toWardName,
        @JsonProperty("to_province_name") String toProvinceName,
        @JsonProperty("is_new_to_address") boolean newToAddress,
        String content,
        int weight,
        int length,
        int width,
        int height,
        @JsonProperty("service_type_id") int serviceTypeId,
        @JsonProperty("cod_amount") int codAmount,
        List<Item> items) {

    public record Item(String name, String code, int quantity, int price) {
    }
}
