package com.project.dacn.service;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.service.catalog.CatalogCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogCacheTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> values;

    private CatalogCache cache;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        cache = new CatalogCache(redis, JsonMapper.builder().build());
    }

    @Test
    void jsonRoundTrip() {
        ProductResponse response = new ProductResponse("PRD_1", "p", "P", "d", null, null, null,
                ProductStatus.ACTIVE, null, null, null, null, null, null, null);
        when(values.get("k")).thenReturn(
                "{\"productId\":\"PRD_1\",\"slug\":\"p\",\"name\":\"P\",\"description\":\"d\","
                        + "\"status\":\"ACTIVE\"}");

        Optional<ProductResponse> cached = cache.get("k",
                new TypeReference<ProductResponse>() { });

        assertThat(cached).contains(response);
    }

    @Test
    void malformedJsonIsAMissAndDeletesTheKey() {
        when(values.get("k")).thenReturn("{not json");

        assertThat(cache.get("k", new TypeReference<ProductResponse>() { })).isEmpty();
        verify(redis).delete("k");
    }

    @Test
    void redisDownGetIsAMiss() {
        when(values.get("k")).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get("k", new TypeReference<ProductResponse>() { })).isEmpty();
    }

    @Test
    void redisDownGenerationIsEmptyNeverZero() {
        when(values.get("pim:product:gen")).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.currentGeneration("product")).isEqualTo(OptionalLong.empty());
    }

    @Test
    void missingCounterIsSeededWithEpochMillis() {
        when(values.get("pim:product:gen")).thenReturn(null, "1700000000000");
        when(values.setIfAbsent(eq("pim:product:gen"), anyString())).thenReturn(true);

        long before = System.currentTimeMillis();
        assertThat(cache.currentGeneration("product")).hasValue(1700000000000L);
        assertThat(System.currentTimeMillis()).isGreaterThanOrEqualTo(before);
        verify(values).setIfAbsent(eq("pim:product:gen"), anyString());
    }

    @Test
    void corruptGenerationIsResetAndBypassed() {
        when(values.get("pim:product:gen")).thenReturn("not-a-number");

        assertThat(cache.currentGeneration("product")).isEqualTo(OptionalLong.empty());
        verify(redis).delete("pim:product:gen");
    }

    @Test
    void bumpSeedsBeforeIncrementing() {
        cache.bumpGeneration("product");

        InOrder order = inOrder(values);
        order.verify(values).setIfAbsent(eq("pim:product:gen"), anyString());
        order.verify(values).increment("pim:product:gen");
    }

    @Test
    void failedBumpRetriesOnceThenGivesUpWithoutThrowing() {
        when(values.setIfAbsent(eq("pim:product:gen"), anyString())).thenReturn(true);
        when(values.increment("pim:product:gen"))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> cache.bumpGeneration("product")).doesNotThrowAnyException();
        verify(values, times(2)).increment("pim:product:gen");
    }

    @Test
    void bumpRecoversWhenTheRetrySucceeds() {
        when(values.setIfAbsent(eq("pim:product:gen"), anyString())).thenReturn(true);
        when(values.increment("pim:product:gen"))
                .thenThrow(new RedisConnectionFailureException("blip"))
                .thenReturn(2L);

        assertThatCode(() -> cache.bumpGeneration("product")).doesNotThrowAnyException();
        verify(values, times(2)).increment("pim:product:gen");
    }

    @Test
    void afterCommitRunsImmediatelyWithoutTransaction() {
        AtomicInteger calls = new AtomicInteger();

        cache.afterCommit(calls::incrementAndGet);

        assertThat(calls).hasValue(1);
    }

    @Test
    void afterCommitDefersUntilCommitWhenTransactionIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            AtomicInteger calls = new AtomicInteger();
            cache.afterCommit(calls::incrementAndGet);

            assertThat(calls).hasValue(0);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(calls).hasValue(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void serializationFailureLogsAndDoesNotWrite() {
        Object cyclic = new Object() {
            public Object getSelf() {
                return this;
            }
        };

        assertThatCode(() -> cache.put("k", cyclic, Duration.ofMinutes(1)))
                .doesNotThrowAnyException();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
