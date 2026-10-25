package com.project.dacn.integration.payment.vnpay;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment.vnpay")
public class VnpayProperties {
    private String tmnCode;
    private String hashSecret;
    private String payUrl;
    private String returnUrl;
    private String ipnUrl;
    private String version = "2.1.0";
    private String command = "pay";
    private String currCode = "VND";
    private String orderType = "other";
    private String locale = "vn";
}
