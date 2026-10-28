package com.project.dacn;

import com.project.dacn.controller.HealthController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    StringRedisTemplate redis;

    @Test
    @SuppressWarnings("unchecked")
    void reportsPingWithoutWritingAKey() {
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");

        assertThat(new HealthController(redis).redis()).containsEntry("redis", "PONG");
        verify(redis, never()).opsForValue();
    }
}
