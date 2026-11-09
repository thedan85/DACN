package com.project.dacn.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The bundles live under {@code messages/}, not at the classpath root, so the
 * Spring Boot default basename ({@code messages}) does not find them. Defining
 * this bean makes every {@code error.*} key resolve through the locale-aware
 * bundle instead of silently falling back to the key text.
 */
@Configuration
public class MessageSourceConfig {

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
