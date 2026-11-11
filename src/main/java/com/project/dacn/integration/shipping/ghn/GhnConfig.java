package com.project.dacn.integration.shipping.ghn;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class GhnConfig {

    @Bean
    public RestClient.Builder ghnRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public SimpleClientHttpRequestFactory ghnRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    @Bean
    public RestClient ghnRestClient(RestClient.Builder builder, GhnProperties properties,
            SimpleClientHttpRequestFactory ghnRequestFactory) {
        RestClient.Builder configured = builder.requestFactory(ghnRequestFactory);
        if (StringUtils.hasText(properties.getBaseUrl())) {
            configured.baseUrl(properties.getBaseUrl());
        }
        return configured.build();
    }
}
