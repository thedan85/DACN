package com.project.dacn.controller;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final StringRedisTemplate redisTemplate;

    public HealthController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/redis")
    public Map<String, String> redis() {
        // A dependency outage must be reported as data, not as an unhandled
        // 500 with a stack trace. The catalog cache and rate limiter already
        // fail open, so the app stays usable when Redis is unreachable.
        try {
            return Map.of("redis", String.valueOf(redisTemplate.execute(RedisConnection::ping)));
        } catch (DataAccessException e) {
            return Map.of("redis", "DOWN");
        }
    }
}
