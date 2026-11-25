package com.project.dacn.messaging.rabbit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "app.notifications.rabbit")
public class NotificationRabbitProperties {
    private static final Set<String> KNOWN_FORBIDDEN_CHECKS =
            Set.of("feature-flags", "policies", "operator-policies");

    private String managementUrl;
    private String managementUsername;
    private String managementPassword;
    private String expectedVersionPrefix;
    private boolean cloudamqpLittleLemur;
    private Set<String> cloudAllowForbiddenChecks = Set.of();
    private Duration expectedQueueIdleExpiry;
    private Duration confirmTimeout;

    public void setCloudAllowForbiddenChecks(Set<String> checks) {
        if (!KNOWN_FORBIDDEN_CHECKS.containsAll(checks)) {
            throw new IllegalArgumentException("Unknown RabbitMQ management check in cloud allowlist");
        }
        cloudAllowForbiddenChecks = Set.copyOf(checks);
    }
}
