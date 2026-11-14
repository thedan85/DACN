package com.project.dacn.service.order;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Latches prove actual overlap and draining; no sleeps stand in for concurrency evidence. */
class OrderExpiryWorkerConcurrencyTest {
    private final OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
    private final CountDownLatch failureRecorded = new CountDownLatch(1);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry() {
        @Override
        protected Counter newCounter(Meter.Id id) {
            Counter delegate = super.newCounter(id);
            if (!id.getName().equals(OrderExpiryWorker.FAILURE_METRIC)) return delegate;
            return new Counter() {
                @Override
                public void increment(double amount) {
                    delegate.increment(amount);
                    failureRecorded.countDown();
                }

                @Override
                public double count() {
                    return delegate.count();
                }

                @Override
                public Meter.Id getId() {
                    return delegate.getId();
                }
            };
        }
    };
    private final OrderExpiryWorker worker =
            new OrderExpiryWorker(lifecycle, new ReservationPolicy(true, false, false), registry);

    @Test
    void fourRealOverlappingCallsShareOneFiftyAttemptBudgetAndLeaveNoThreads() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        onClaim(invocation -> {
            threads.add(Thread.currentThread());
            int number = calls.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (number <= 4) {
                    entered.countDown();
                    await(release);
                }
                return true;
            } finally {
                active.decrementAndGet();
            }
        });
        try (var driver = Executors.newSingleThreadExecutor()) {
            var sweep = driver.submit(worker::sweep);
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).as("four transactions must really overlap").isTrue();
                assertThat(calls.get()).isEqualTo(4);
                assertThat(active.get()).isEqualTo(4);
            } finally {
                release.countDown();
            }
            sweep.get(10, TimeUnit.SECONDS);
        }
        assertThat(calls.get()).isEqualTo(50);
        assertThat(peak.get()).isEqualTo(4);
        assertThat(active.get()).isZero();
        assertThat(threads).hasSize(4).allMatch(thread -> !thread.isAlive());
        assertThat(claims()).isEqualTo(50);
        assertThat(failures()).isZero();
    }

    @Test
    void concurrentCandidateFailuresUseOnlyFiftyAttemptsAndImmutableBoundedSnapshots() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var snapshots = new ConcurrentLinkedQueue<Set<String>>();
        onClaim(invocation -> {
            Set<String> snapshot = invocation.getArguments().length == 0
                    ? Set.of() : invocation.getArgument(0);
            snapshots.add(snapshot);
            int number = calls.incrementAndGet();
            if (number <= 4) {
                entered.countDown();
                await(release);
            }
            throw new ExpiryCandidateFailure("bad-" + number, new IllegalStateException("stock overflow"));
        });
        try (var driver = Executors.newSingleThreadExecutor()) {
            var sweep = driver.submit(worker::sweep);
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            sweep.get(10, TimeUnit.SECONDS);
        }
        assertThat(calls.get()).isEqualTo(50);
        assertThat(snapshots).anyMatch(snapshot -> !snapshot.isEmpty());
        snapshots.forEach(snapshot -> {
            assertThat(snapshot.size()).isLessThanOrEqualTo(50);
            assertThatThrownBy(() -> snapshot.add("must-not-mutate"))
                    .isInstanceOf(UnsupportedOperationException.class);
        });
        assertThat(claims()).isZero();
        assertThat(failures()).isEqualTo(50);
    }

    @Test
    void pauseStopsNewAttemptsButDrainsTheFourAlreadyStarted() throws Exception {
        assertStopAndDrain(false, false);
    }

    @Test
    void infrastructureFailureStopsNewAttemptsButDrainsAndCountsAllStartedOutcomes() throws Exception {
        assertStopAndDrain(true, false);
    }

    @Test
    void fatalErrorIsPropagatedOnlyAfterOtherStartedCallsFinish() throws Exception {
        assertStopAndDrain(false, true);
    }

    private void assertStopAndDrain(boolean infrastructure, boolean fatal) throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch trigger = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        onClaim(invocation -> {
            int number = calls.incrementAndGet();
            entered.countDown();
            try {
                if (number == 1) {
                    await(trigger);
                    if (!infrastructure && !fatal) {
                        ReflectionTestUtils.setField(worker, "reservationPolicy",
                                new ReservationPolicy(true, false, true));
                    }
                    stopping.countDown();
                    if (infrastructure) throw new DataAccessResourceFailureException("connection unavailable");
                    if (fatal) throw new AssertionError("fatal claim failure");
                } else {
                    await(release);
                }
                return true;
            } finally {
                completed.incrementAndGet();
            }
        });
        try (var driver = Executors.newSingleThreadExecutor()) {
            var sweep = driver.submit(worker::sweep);
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                trigger.countDown();
                assertThat(stopping.await(3, TimeUnit.SECONDS)).isTrue();
                if (infrastructure || fatal) {
                    assertThat(failureRecorded.await(3, TimeUnit.SECONDS))
                            .as("failure has reached worker handling before other calls finish").isTrue();
                }
                assertThat(sweep.isDone()).as("other started claims are still blocked").isFalse();
                assertThat(calls.get()).isEqualTo(4);
            } finally {
                trigger.countDown();
                release.countDown();
            }
            if (fatal) {
                assertThatThrownBy(() -> sweep.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(AssertionError.class);
            } else {
                sweep.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(calls.get()).isEqualTo(4);
        assertThat(completed.get()).isEqualTo(4);
        assertThat(claims()).isEqualTo(infrastructure || fatal ? 3 : 4);
        assertThat(failures()).isEqualTo(infrastructure || fatal ? 1 : 0);
    }

    @Test
    void coordinatorInterruptionDrainsWithoutInterruptingClaimThreadsAndRestoresItsFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean returnedInterrupted = new AtomicBoolean();
        AtomicBoolean claimInterrupted = new AtomicBoolean();
        onClaim(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                claimInterrupted.set(true);
                throw error;
            }
            return true;
        });
        Thread driver = new Thread(() -> {
            worker.sweep();
            returnedInterrupted.set(Thread.currentThread().isInterrupted());
        });
        driver.start();
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            driver.interrupt();
            assertThat(driver.isAlive()).isTrue();
        } finally {
            release.countDown();
            driver.join(10_000);
        }
        assertThat(driver.isAlive()).isFalse();
        assertThat(returnedInterrupted).isTrue();
        assertThat(claimInterrupted).isFalse();
        assertThat(calls.get()).isEqualTo(4);
        assertThat(claims()).isEqualTo(4);
        assertThat(failures()).isZero();
    }

    private void onClaim(Answer<Boolean> answer) {
        doAnswer(answer).when(lifecycle).expireNextDue();
        doAnswer(answer).when(lifecycle).expireNextDue(anySet());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("test latch released").isTrue();
    }

    private double claims() {
        return registry.get(OrderExpiryWorker.CLAIMS_METRIC).counter().count();
    }

    private double failures() {
        return registry.get(OrderExpiryWorker.FAILURE_METRIC).counter().count();
    }
}
