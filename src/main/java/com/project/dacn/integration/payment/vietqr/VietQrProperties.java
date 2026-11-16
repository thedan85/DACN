package com.project.dacn.integration.payment.vietqr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment.vietqr")
public class VietQrProperties {
    private String bankId;
    private String accountNo;
    private String accountName;
    private String template = "compact2";
    private String imageBaseUrl = "https://img.vietqr.io/image";
}
