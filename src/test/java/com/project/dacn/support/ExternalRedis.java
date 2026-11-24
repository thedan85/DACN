package com.project.dacn.support;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Connects the tests to TEST_REDIS_URL without ever printing its credentials. */
public final class ExternalRedis {

    private ExternalRedis() {
    }

    public static boolean isConfigured() {
        String url = System.getenv("TEST_REDIS_URL");
        return url != null && !url.isBlank();
    }

    public static String requiredUrl() {
        String url = System.getenv("TEST_REDIS_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("TEST_REDIS_URL is not set");
        }
        return url;
    }

    public static LettuceConnectionFactory newConnectionFactory() {
        URI uri = URI.create(requiredUrl());
        String scheme = uri.getScheme();
        if (!"redis".equalsIgnoreCase(scheme) && !"rediss".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("TEST_REDIS_URL must use redis:// or rediss://");
        }
        int port = uri.getPort() == -1 ? 6379 : uri.getPort();
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(uri.getHost(), port);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            String username = decode(parts[0]);
            if (!username.isEmpty()) {
                standalone.setUsername(username);
            }
            if (parts.length == 2) {
                standalone.setPassword(decode(parts[1]));
            }
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            standalone.setDatabase(Integer.parseInt(path.substring(1)));
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client =
                LettuceClientConfiguration.builder();
        if ("rediss".equalsIgnoreCase(scheme)) {
            client.useSsl();
        }
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(standalone, client.build());
        factory.afterPropertiesSet();
        return factory;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
