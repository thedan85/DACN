package com.project.dacn.security;

import com.project.dacn.common.exception.RateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window limiter for login attempts.
 *
 * ponytail: per-instance memory only, resets on restart and is not shared
 * across replicas. Move to Redis (already a dev dependency) when the API is
 * deployed with more than one instance.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 10;
    private static final long WINDOW_MS = Duration.ofMinutes(1).toMillis();

    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public void check(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> window = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= MAX_ATTEMPTS) {
                throw new RateLimitExceededException("too many login attempts, try again later");
            }
            window.addLast(now);
        }
    }
}
