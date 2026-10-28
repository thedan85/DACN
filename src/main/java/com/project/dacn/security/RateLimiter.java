package com.project.dacn.security;

import com.project.dacn.common.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@RequiredArgsConstructor
public class RateLimiter {

    private static final RedisScript<Long> SLIDING_WINDOW = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local window = tonumber(ARGV[1])
            local max = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
            if redis.call('ZCARD', KEYS[1]) >= max then
                return 0
            end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AtomicLong lastWarn = new AtomicLong();

    public void check(String key, int max, Duration window) {
        try {
            Long result = redis.execute(SLIDING_WINDOW, List.of(key),
                    Long.toString(window.toMillis()),
                    Integer.toString(max),
                    UUID.randomUUID().toString());
            if (result != null && result == 0L) {
                throw new RateLimitExceededException("too many requests, try again later");
            }
        } catch (DataAccessException e) {
            long now = System.currentTimeMillis();
            long last = lastWarn.get();
            if (now - last >= 60_000 && lastWarn.compareAndSet(last, now)) {
                log.warn("Rate limiter unavailable, failing open: {}", e.getMessage());
            }
        }
    }
}
