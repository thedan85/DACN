package com.project.dacn.security;

import com.project.dacn.common.exception.RateLimitExceededException;
import com.project.dacn.support.ExternalRedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RateLimiterRedisTest {

    static final String PREFIX = "it:" + UUID.randomUUID() + ":";

    static RateLimiter limiter;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory factory;
    static GenericContainer<?> container;

    @BeforeAll
    static void connect() {
        if (ExternalRedis.isConfigured()) {
            factory = ExternalRedis.newConnectionFactory();
            System.out.println("RateLimiterRedisTest backend: TEST_REDIS_URL");
        } else {
            boolean docker = DockerClientFactory.instance().isDockerAvailable();
            Assumptions.assumeTrue(docker, "TEST_REDIS_URL is unset and Docker is unavailable");
            container = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            container.start();
            factory = new LettuceConnectionFactory(
                    container.getHost(), container.getMappedPort(6379));
            factory.afterPropertiesSet();
        }
        redis = new StringRedisTemplate(factory);
        limiter = new RateLimiter(redis);
    }

    @AfterAll
    static void cleanup() {
        try {
            if (redis != null) {
                List<String> keys = List.of(PREFIX + "max", PREFIX + "expiry", PREFIX + "ttl", PREFIX + "strict");
                Long deleted = redis.delete(keys);
                Long remaining = redis.countExistingKeys(keys);
                assertThat(remaining).isZero();
                System.out.printf("RateLimiterRedisTest cleanup: deleted=%d, remaining=%d%n", deleted, remaining);
            }
        } finally {
            if (factory != null) {
                factory.destroy();
            }
            if (container != null) {
                container.stop();
            }
        }
    }

    @Test
    void admitsExactlyMaxThenRejects() {
        for (int i = 0; i < 10; i++) {
            assertThatNoException().isThrownBy(() ->
                    limiter.check(PREFIX + "max", 10, Duration.ofMinutes(1)));
        }

        assertThatThrownBy(() -> limiter.check(PREFIX + "max", 10, Duration.ofMinutes(1)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void strictCheckUsesTheRealSlidingWindowAndRejectsExhaustion() {
        assertThatNoException().isThrownBy(() ->
                limiter.checkStrict(PREFIX + "strict", 2, Duration.ofMinutes(1)));
        assertThatNoException().isThrownBy(() ->
                limiter.checkStrict(PREFIX + "strict", 2, Duration.ofMinutes(1)));
        assertThatThrownBy(() -> limiter.checkStrict(PREFIX + "strict", 2, Duration.ofMinutes(1)))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(redis.getExpire(PREFIX + "strict", TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void expiresAfterWindow() throws InterruptedException {
        limiter.check(PREFIX + "expiry", 1, Duration.ofSeconds(1));
        assertThatThrownBy(() -> limiter.check(PREFIX + "expiry", 1, Duration.ofSeconds(1)))
                .isInstanceOf(RateLimitExceededException.class);

        Thread.sleep(1200);

        assertThatCode(() -> limiter.check(PREFIX + "expiry", 1, Duration.ofSeconds(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void setsTtl() {
        limiter.check(PREFIX + "ttl", 10, Duration.ofSeconds(30));

        assertThat(redis.getExpire(PREFIX + "ttl", TimeUnit.MILLISECONDS)).isPositive();
    }
}
