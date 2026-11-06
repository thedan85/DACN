package com.project.dacn.messaging.rabbit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "app.notifications.rabbit")
public class NotificationRabbitProperties {
    private String managementUrl;
    private String managementUsername;
    private String managementPassword;
    private String expectedVersionPrefix;
    private Duration confirmTimeout;
}
