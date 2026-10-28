package com.project.dacn.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.project.dacn.common.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    StringRedisTemplate redis;

    @InjectMocks
    RateLimiter limiter;

    @SuppressWarnings("unchecked")
    private void stubScriptResult(Long result) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(result);
    }

    @Test
    void underLimit_allowed() {
        stubScriptResult(1L);

        assertThatCode(() -> limiter.check("rl:login:1.2.3.4", 10, Duration.ofMinutes(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void atLimit_blocked() {
        stubScriptResult(0L);

        assertThatThrownBy(() -> limiter.check("rl:login:1.2.3.4", 10, Duration.ofMinutes(1)))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("too many requests, try again later");
    }

    @Test
    void redisDown_failsOpen() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> limiter.check("rl:login:1.2.3.4", 10, Duration.ofMinutes(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void warningThrottledOncePerMinute() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimiter.class);
        logger.addAppender(appender);
        try {
            limiter.check("rl:login:1.2.3.4", 10, Duration.ofMinutes(1));
            limiter.check("rl:login:1.2.3.4", 10, Duration.ofMinutes(1));

            assertThat(appender.list).hasSize(1);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
