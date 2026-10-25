package com.project.dacn.config;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisConfigTest {

    private static LettuceClientConfiguration config(Duration command, Duration connect) {
        DataRedisProperties properties = new DataRedisProperties();
        properties.setHost("localhost");
        properties.setPort(6379);
        return RedisConfig.clientConfiguration(properties, command, connect);
    }

    @Test
    void configuresShortTimeoutsAndRejectsCommandsWhileDisconnected() {
        LettuceClientConfiguration config = config(Duration.ofMillis(200), Duration.ofMillis(500));

        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofMillis(200));
        ClientOptions options = config.getClientOptions().orElseThrow();
        assertThat(options.getDisconnectedBehavior())
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void timeoutsAreConfigurable() {
        LettuceClientConfiguration config = config(Duration.ofSeconds(3), Duration.ofSeconds(7));

        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofSeconds(7));
    }
}
