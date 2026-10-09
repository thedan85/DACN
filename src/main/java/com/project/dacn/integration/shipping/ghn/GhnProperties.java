package com.project.dacn.integration.shipping.ghn;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "shipping.ghn")
public class GhnProperties {
    private String baseUrl;
    private String token;
    private String shopId;
}
