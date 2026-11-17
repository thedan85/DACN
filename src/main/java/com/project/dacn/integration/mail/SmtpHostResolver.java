package com.project.dacn.integration.mail;

import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Bounds host resolution away from the SMTP sender thread. A single daemon thread
 * runs the non-interruptible lookup; callers wait on an interruptible latch under
 * a hard cap. Concurrent callers for the same host share one in-progress lookup,
 * and a successful address is cached for a bounded time. Nothing here opens a
 * socket, so every failure mode returns before any network connection exists.
 */
public final class SmtpHostResolver implements AutoCloseable {

    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    static final Duration ADDRESS_CACHE_TTL = Duration.ofSeconds(60);
    static final String DNS_FAILURE_CODE = "SMTP_DNS_FAILURE";

    @FunctionalInterface
    public interface Lookup {
        InetAddress[] lookup(String host) throws Exception;
    }

    public enum Kind { RESOLVED, TIMEOUT, FAILURE, ABORTED }

    public record Resolution(Kind kind, InetAddress address, String failureCode) {
        public boolean resolved() {
            return kind == Kind.RESOLVED;
        }
    }

    private final Lookup lookup;
    private final Duration timeout;
    private final Duration cacheTtl;
    private final BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Request> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread worker;

    public SmtpHostResolver() {
        this(InetAddress::getAllByName, LOOKUP_TIMEOUT, ADDRESS_CACHE_TTL);
    }

    public SmtpHostResolver(Lookup lookup) {
        this(lookup, LOOKUP_TIMEOUT, ADDRESS_CACHE_TTL);
    }

    SmtpHostResolver(Lookup lookup, Duration timeout, Duration cacheTtl) {
        this.lookup = lookup;
        this.timeout = timeout;
        this.cacheTtl = cacheTtl;
    }

    public Resolution resolve(String host, BooleanSupplier aborted) {
        return resolve(host, timeout, aborted);
    }

    public Resolution resolve(String host, Duration wait, BooleanSupplier aborted) {
        if (closed.get() || aborted.getAsBoolean()) {
            return new Resolution(Kind.ABORTED, null, null);
        }
        Cached cached = cache.get(host);
        if (cached != null && !cached.expired()) {
            return new Resolution(Kind.RESOLVED, cached.address, null);
        }
        if (cached != null) {
            cache.remove(host, cached);
        }

        Request request = inFlight.computeIfAbsent(host, h -> {
            Request created = new Request(h);
            queue.add(created);
            startWorker();
            return created;
        });

        boolean completed;
        try {
            completed = request.done.await(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resolution(Kind.ABORTED, null, null);
        }

        // Abort, close and the thread's own deadline all dominate a lookup result.
        // A late completion is never handed to this caller.
        if (aborted.getAsBoolean() || closed.get()) {
            return new Resolution(Kind.ABORTED, null, null);
        }
        if (!completed || !request.completed) {
            return new Resolution(Kind.TIMEOUT, null, null);
        }
        if (request.failure != null || request.address == null) {
            return new Resolution(Kind.FAILURE, null, DNS_FAILURE_CODE);
        }
        cache.put(host, new Cached(request.address, System.nanoTime() + cacheTtl.toNanos()));
        return new Resolution(Kind.RESOLVED, request.address, null);
    }

    private void startWorker() {
        if (worker == null) {
            synchronized (this) {
                if (worker == null) {
                    Thread thread = new Thread(this::work, "smtp-host-resolver");
                    thread.setDaemon(true);
                    worker = thread;
                    thread.start();
                }
            }
        }
    }

    private void work() {
        while (!closed.get()) {
            Request request;
            try {
                request = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                InetAddress[] addresses = lookup.lookup(request.host);
                if (!closed.get()) {
                    request.address = addresses != null && addresses.length > 0 ? addresses[0] : null;
                }
            } catch (Throwable failure) {
                if (!closed.get()) {
                    request.failure = failure;
                }
            } finally {
                request.completed = true;
                request.done.countDown();
                inFlight.remove(request.host, request);
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cache.clear();
            for (Request pending : inFlight.values()) {
                pending.completed = true;
                pending.failure = new IllegalStateException("resolver closed");
                pending.done.countDown();
            }
            inFlight.clear();
            Thread thread = worker;
            if (thread != null) {
                thread.interrupt(); // frees an idle take(); a running lookup stays bounded by its caller
            }
        }
    }

    private static final class Request {
        final String host;
        final CountDownLatch done = new CountDownLatch(1);
        volatile InetAddress address;
        volatile Throwable failure;
        volatile boolean completed;

        Request(String host) {
            this.host = host;
        }
    }

    private static final class Cached {
        final InetAddress address;
        final long expiresAtNanos;

        Cached(InetAddress address, long expiresAtNanos) {
            this.address = address;
            this.expiresAtNanos = expiresAtNanos;
        }

        boolean expired() {
            return System.nanoTime() >= expiresAtNanos;
        }
    }
}
