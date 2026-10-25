package com.project.dacn.service.catalog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

@Slf4j
@Component
public class CatalogCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public CatalogCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public <T> Optional<T> get(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(mapper.readValue(json, type));
        } catch (JacksonException e) {
            log.debug("Unreadable catalog cache value at {}, treating as a miss", key, e);
            deleteQuietly(key);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Catalog cache read failed at {}, falling back to the database", key, e);
            return Optional.empty();
        }
    }

    public void put(String key, Object value, Duration ttl) {
        String json;
        try {
            json = mapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            log.error("Failed to serialize catalog cache value for {}", key, e);
            return;
        }
        try {
            redis.opsForValue().set(key, json, ttl);
        } catch (RuntimeException e) {
            log.debug("Catalog cache write failed at {}", key, e);
        }
    }

    public OptionalLong currentGeneration(String scope) {
        String key = CatalogCacheKeys.generationKey(scope);
        try {
            String current = redis.opsForValue().get(key);
            if (current == null) {
                redis.opsForValue().setIfAbsent(key, Long.toString(System.currentTimeMillis()));
                current = redis.opsForValue().get(key);
            }
            return current == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(current));
        } catch (NumberFormatException e) {
            log.debug("Invalid catalog generation at {}, bypassing the cache", key, e);
            return OptionalLong.empty();
        } catch (RuntimeException e) {
            log.debug("Catalog cache generation read failed at {}, bypassing the cache", key, e);
            return OptionalLong.empty();
        }
    }

    public void bumpGeneration(String scope) {
        String key = CatalogCacheKeys.generationKey(scope);
        try {
            increment(key);
        } catch (RuntimeException first) {
            try {
                increment(key);
            } catch (RuntimeException second) {
                // A lost bump leaves stale data until TTL, so it is worth one retry and a loud log.
                log.warn("Failed to bump catalog generation at {} after a retry; cached data may be stale", key, second);
            }
        }
    }

    public void afterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Catalog cache after-commit action failed", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }

    public void bumpAfterCommit(String... scopes) {
        afterCommit(() -> {
            for (String scope : scopes) {
                bumpGeneration(scope);
            }
        });
    }

    private void increment(String key) {
        redis.opsForValue().setIfAbsent(key, Long.toString(System.currentTimeMillis()));
        redis.opsForValue().increment(key);
    }

    private void deleteQuietly(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.debug("Failed to delete unreadable catalog cache value at {}", key, e);
        }
    }
}
