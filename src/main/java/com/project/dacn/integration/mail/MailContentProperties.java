package com.project.dacn.integration.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trusted, server-owned mail content configuration. No event, browser or
 * recipient value is ever used as a base URL or sender identity.
 */
@ConfigurationProperties(prefix = "app.notifications.mail")
public record MailContentProperties(
        String storefrontBaseUrl,
        String fromAddress,
        String messageIdDomain) {

    public MailContentProperties {
        if (storefrontBaseUrl == null || storefrontBaseUrl.isBlank()) {
            storefrontBaseUrl = "http://localhost:3000";
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            fromAddress = "no-reply@notifications.local";
        }
        if (messageIdDomain == null || messageIdDomain.isBlank()) {
            messageIdDomain = "notifications.local";
        }
    }

    public MailContentProperties() {
        this(null, null, null);
    }
}
