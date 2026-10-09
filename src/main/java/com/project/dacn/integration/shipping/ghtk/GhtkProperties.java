package com.project.dacn.integration.shipping.ghtk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "shipping.ghtk")
public class GhtkProperties {
    private String baseUrl;
    private String token;
}
