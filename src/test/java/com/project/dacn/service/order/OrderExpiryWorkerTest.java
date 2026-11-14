package com.project.dacn.service.order;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anySet;

/** Worker orchestration only; real scheduled expiry proof runs against PostgreSQL. */
class OrderExpiryWorkerTest {

    private final OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void processesAtMostFiftyClaimsPerTick() {
        when(lifecycle.expireNextDue()).thenReturn(true);
        var worker = new OrderExpiryWorker(lifecycle,
                new ReservationPolicy(true, false, false), registry);

        worker.sweep();

        verify(lifecycle, times(50)).expireNextDue();
        assertThat(claims()).isEqualTo(50);
        assertThat(failures()).isZero();
    }

    @Test
    void stopsWhenNoDueOrderRemains() {
        when(lifecycle.expireNextDue()).thenReturn(true, true, false);
        var worker = new OrderExpiryWorker(lifecycle,
                new ReservationPolicy(true, false, false), registry);

        worker.sweep();

        verify(lifecycle, times(3)).expireNextDue();
        assertThat(claims()).isEqualTo(2);
        assertThat(failures()).isZero();
    }

    @Test
    void inactiveOrSweepPausedPolicyMakesZeroClaims() {
        for (ReservationPolicy policy : new ReservationPolicy[]{
                new ReservationPolicy(false, false, false),
                new ReservationPolicy(true, false, true),
                new ReservationPolicy(false, true, true)}) {
            registry.clear();
            var worker = new OrderExpiryWorker(lifecycle, policy, registry);

            worker.sweep();

            verifyNoInteractions(lifecycle);
            assertThat(claims()).isZero();
            assertThat(failures()).isZero();
        }
    }

    @Test
    void onlinePauseAloneDoesNotStopSweeping() {
        when(lifecycle.expireNextDue()).thenReturn(true, false);
        var worker = new OrderExpiryWorker(lifecycle,
                new ReservationPolicy(true, true, false), registry);

        worker.sweep();

        verify(lifecycle, times(2)).expireNextDue();
        assertThat(claims()).isEqualTo(1);
    }

    @Test
    void rechecksGuardBeforeEveryNewClaim() {
        ReservationPolicy active = new ReservationPolicy(true, false, false);
        ReservationPolicy paused = new ReservationPolicy(true, false, true);
        AtomicInteger calls = new AtomicInteger();
        OrderExpiryWorker[] worker = new OrderExpiryWorker[1];
        when(lifecycle.expireNextDue()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 3) {
                ReflectionTestUtils.setField(worker[0], "reservationPolicy", paused);
            }
            return true;
        });
        worker[0] = new OrderExpiryWorker(lifecycle, active, registry);

        worker[0].sweep();

        verify(lifecycle, times(3)).expireNextDue();
        assertThat(claims()).isEqualTo(3);
        assertThat(failures()).isZero();
    }

    @Test
    void failureStopsTheTickAndIsNotCountedAsAClaim() {
        when(lifecycle.expireNextDue()).thenThrow(new IllegalStateException("stock target not found"));
        var worker = new OrderExpiryWorker(lifecycle,
                new ReservationPolicy(true, false, false), registry);

        worker.sweep();

        verify(lifecycle, times(1)).expireNextDue();
        assertThat(claims()).isZero();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void candidateFailuresConsumeTheSameFiftyAttemptBudget() {
        when(lifecycle.expireNextDue()).thenThrow(
                new ExpiryCandidateFailure("bad-0", new IllegalStateException("stock overflow")));
        when(lifecycle.expireNextDue(anySet())).thenAnswer(invocation -> {
            java.util.Set<String> excluded = invocation.getArgument(0);
            assertThat(excluded).contains("bad-0");
            throw new ExpiryCandidateFailure("bad-" + excluded.size(),
                    new IllegalStateException("stock overflow"));
        });
        var worker = new OrderExpiryWorker(lifecycle, new ReservationPolicy(true, false, false), registry);
        worker.sweep();
        verify(lifecycle, times(1)).expireNextDue();
        verify(lifecycle, times(49)).expireNextDue(anySet());
        assertThat(claims()).isZero();
        assertThat(failures()).isEqualTo(50);
    }

    @Test
    void infrastructureFailureAfterCandidateFailureStopsWithoutCountingSuccess() {
        when(lifecycle.expireNextDue()).thenThrow(
                new ExpiryCandidateFailure("bad", new IllegalStateException("stock overflow")));
        when(lifecycle.expireNextDue(anySet())).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("connection unavailable"));
        var worker = new OrderExpiryWorker(lifecycle, new ReservationPolicy(true, false, false), registry);
        worker.sweep();
        verify(lifecycle, times(1)).expireNextDue();
        verify(lifecycle, times(1)).expireNextDue(anySet());
        assertThat(claims()).isZero();
        assertThat(failures()).isEqualTo(2);
    }

    @Test
    void pauseAfterCandidateFailurePreventsTheNextClaim() {
        var worker = new OrderExpiryWorker(lifecycle, new ReservationPolicy(true, false, false), registry);
        when(lifecycle.expireNextDue()).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(worker, "reservationPolicy", new ReservationPolicy(true, false, true));
            throw new ExpiryCandidateFailure("bad", new IllegalStateException("stock overflow"));
        });
        worker.sweep();
        verify(lifecycle, times(1)).expireNextDue();
        verify(lifecycle, times(0)).expireNextDue(anySet());
        assertThat(claims()).isZero();
        assertThat(failures()).isEqualTo(1);
    }

    private double claims() {
        var counter = registry.find(OrderExpiryWorker.CLAIMS_METRIC).counter();
        return counter == null ? 0 : counter.count();
    }

    private double failures() {
        var counter = registry.find(OrderExpiryWorker.FAILURE_METRIC).counter();
        return counter == null ? 0 : counter.count();
    }
}
