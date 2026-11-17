package com.project.dacn.integration.mail;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.project.dacn.integration.mail.SmtpHostResolver.Kind.ABORTED;
import static com.project.dacn.integration.mail.SmtpHostResolver.Kind.FAILURE;
import static com.project.dacn.integration.mail.SmtpHostResolver.Kind.RESOLVED;
import static com.project.dacn.integration.mail.SmtpHostResolver.Kind.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

/** Bounded, off-sender host resolution: timeout, failure, single-flight, cache, abort. */
class SmtpHostResolverTest {

    private static final java.util.function.BooleanSupplier LIVE = () -> false;

    @Test
    void stalledLookup_timesOutBounded_withSingleFlightAndNoAddress() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SmtpHostResolver.Lookup stalled = host -> {
            calls.incrementAndGet();
            release.await(); // non-interruptible from the caller's point of view
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        };
        ExecutorService pool = daemonPool(2);
        try (SmtpHostResolver resolver = new SmtpHostResolver(
                stalled, Duration.ofMillis(400), Duration.ofSeconds(60))) {
            CountDownLatch start = new CountDownLatch(1);
            Future<SmtpHostResolver.Resolution> a = pool.submit(() -> {
                start.await();
                return resolver.resolve("smtp.stalled.test", LIVE);
            });
            Future<SmtpHostResolver.Resolution> b = pool.submit(() -> {
                start.await();
                return resolver.resolve("smtp.stalled.test", LIVE);
            });
            long began = System.currentTimeMillis();
            start.countDown();
            SmtpHostResolver.Resolution ra = a.get(4, TimeUnit.SECONDS);
            SmtpHostResolver.Resolution rb = b.get(4, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - began;

            assertThat(ra.kind()).isEqualTo(TIMEOUT);
            assertThat(rb.kind()).isEqualTo(TIMEOUT);
            assertThat(ra.address()).isNull();
            assertThat(rb.address()).isNull();
            assertThat(calls.get()).as("single in-progress lookup shared").isEqualTo(1);
            assertThat(elapsed).as("bounded wall time").isLessThan(4_000);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void lookupFailure_returnsFailureCodeWithNoAddress() throws Exception {
        SmtpHostResolver.Lookup failing = host -> {
            throw new UnknownHostException("blocked");
        };
        try (SmtpHostResolver resolver = new SmtpHostResolver(
                failing, Duration.ofMillis(500), Duration.ofSeconds(60))) {
            SmtpHostResolver.Resolution result = resolver.resolve("bad.test", LIVE);
            assertThat(result.kind()).isEqualTo(FAILURE);
            assertThat(result.failureCode()).isEqualTo("SMTP_DNS_FAILURE");
            assertThat(result.address()).isNull();
        }
    }

    @Test
    void successfulResolution_isCachedThenExpires() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SmtpHostResolver.Lookup counting = host -> {
            calls.incrementAndGet();
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        };
        try (SmtpHostResolver resolver = new SmtpHostResolver(
                counting, Duration.ofMillis(500), Duration.ofMillis(150))) {
            SmtpHostResolver.Resolution first = resolver.resolve("smtp.cached.test", LIVE);
            SmtpHostResolver.Resolution second = resolver.resolve("smtp.cached.test", LIVE);
            assertThat(first.kind()).isEqualTo(RESOLVED);
            assertThat(first.address()).isNotNull();
            assertThat(second.kind()).isEqualTo(RESOLVED);
            assertThat(calls.get()).as("cache hit, no second lookup").isEqualTo(1);

            Thread.sleep(220);
            SmtpHostResolver.Resolution third = resolver.resolve("smtp.cached.test", LIVE);
            assertThat(third.kind()).isEqualTo(RESOLVED);
            assertThat(calls.get()).as("cache expired, fresh lookup").isEqualTo(2);
        }
    }

    @Test
    void abortedOrClosed_resolutionIsUnusableWithoutLookup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SmtpHostResolver.Lookup counting = host -> {
            calls.incrementAndGet();
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        };
        SmtpHostResolver resolver = new SmtpHostResolver(
                counting, Duration.ofMillis(500), Duration.ofSeconds(60));
        AtomicBoolean aborted = new AtomicBoolean(true);
        assertThat(resolver.resolve("smtp.abort.test", aborted::get).kind()).isEqualTo(ABORTED);
        assertThat(calls.get()).isZero();

        resolver.close();
        assertThat(resolver.resolve("smtp.abort.test", LIVE).kind()).isEqualTo(ABORTED);
        assertThat(calls.get()).isZero();
    }

    private static ExecutorService daemonPool(int size) {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "resolver-test");
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(size, factory);
    }
}
