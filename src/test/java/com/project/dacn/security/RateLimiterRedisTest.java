package com.project.dacn.security;

import com.project.dacn.common.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class RateLimiterRedisTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RateLimiter limiter;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        limiter = new RateLimiter(redis);
    }

    @Test
    void admitsExactlyMaxThenRejects() {
        for (int i = 0; i < 10; i++) {
            assertThatCode(() -> limiter.check("it:max", 10, Duration.ofMinutes(1)))
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.check("it:max", 10, Duration.ofMinutes(1)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void expiresAfterWindow() throws InterruptedException {
        limiter.check("it:expiry", 1, Duration.ofSeconds(1));
        assertThatThrownBy(() -> limiter.check("it:expiry", 1, Duration.ofSeconds(1)))
                .isInstanceOf(RateLimitExceededException.class);

        Thread.sleep(1200);

        assertThatCode(() -> limiter.check("it:expiry", 1, Duration.ofSeconds(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void setsTtl() {
        limiter.check("it:ttl", 10, Duration.ofSeconds(30));

        assertThat(redis.getExpire("it:ttl", TimeUnit.MILLISECONDS)).isPositive();
    }
}
