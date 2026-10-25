package com.project.dacn.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class RedisConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            DataRedisProperties properties,
            @Value("${catalog.cache.redis.command-timeout:200ms}") Duration commandTimeout,
            @Value("${catalog.cache.redis.connect-timeout:500ms}") Duration connectTimeout) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                properties.getHost(), properties.getPort());
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            standalone.setUsername(properties.getUsername());
        }
        standalone.setPassword(properties.getPassword());
        return new LettuceConnectionFactory(
                standalone, clientConfiguration(properties, commandTimeout, connectTimeout));
    }

    // Fail open is only fast if Redis fails fast: short timeouts, no command buffering while disconnected.
    static LettuceClientConfiguration clientConfiguration(
            DataRedisProperties properties, Duration commandTimeout, Duration connectTimeout) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder()
                        .clientOptions(ClientOptions.builder()
                                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                                .socketOptions(SocketOptions.builder()
                                        .connectTimeout(connectTimeout)
                                        .build())
                                .build())
                        .commandTimeout(commandTimeout);
        if (properties.getSsl() != null && properties.getSsl().isEnabled()) {
            builder.useSsl();
        }
        return builder.build();
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
