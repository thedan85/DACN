package com.project.dacn.integration.shipping.ghn;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "shipping.ghn")
public class GhnProperties {
    private String baseUrl;

    @ToString.Exclude
    private String token;

    private String shopId;

    @ToString.Exclude
    private String webhookSecret;
}
