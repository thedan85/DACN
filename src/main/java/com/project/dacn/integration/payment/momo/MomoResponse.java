package com.project.dacn.integration.payment.momo;

import lombok.Data;

@Data
public class MomoResponse {
    private int resultCode;
    private String message;
    private String payUrl;
}
