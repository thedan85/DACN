package com.project.dacn.integration.payment.momo;

import lombok.Data;

@Data
public class MomoRequest {
    private String orderId;
    private long amount;
    private String orderInfo;
}
