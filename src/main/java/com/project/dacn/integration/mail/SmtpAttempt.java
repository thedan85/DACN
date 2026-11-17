package com.project.dacn.integration.mail;

import com.project.dacn.service.engagement.EmailDeliveryService;
import jakarta.mail.Session;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * State owned by exactly one SMTP attempt: the absolute deadline, the raw and
 * TLS-wrapped sockets created for it, and the watchdog that severs them.
 * Nothing here is shared between attempts, so a stale watchdog can only close
 * this attempt's sockets.
 */
public final class SmtpAttempt {

    public static final Duration ATTEMPT_DEADLINE = Duration.ofSeconds(25);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final EmailDeliveryService.Claim claim;
    private final Thread sender;
    private final Duration deadline;
    private final long deadlineNanos;
    private final AtomicBoolean aborted = new AtomicBoolean();
    private final SocketRegistry registry;
    private final Session session;
    private ScheduledFuture<?> watchdogTask;
    private ScheduledExecutorService watchdogScheduler;

    public SmtpAttempt(EmailDeliveryService.Claim claim, Thread sender, Duration deadline,
                       SmtpAttemptExecutor.Target target) {
        this.claim = claim;
        this.sender = sender;
        this.deadline = deadline;
        // Single monotonic clock domain. The DB lease timestamp is metadata only;
        // comparing it with the JVM wall clock would be clock-skew dependent.
        this.deadlineNanos = claim.claimedAtNanos() + deadline.toNanos();
        this.registry = new SocketRegistry(aborted);

        AttemptSocketFactory plain = new AttemptSocketFactory(registry);
        AttemptSocketFactory.Tls tls = new AttemptSocketFactory.Tls(
                (SSLSocketFactory) SSLSocketFactory.getDefault(), registry);
        Properties props = new Properties();
        props.put("mail.smtp.socketFactory", plain);
        props.put("mail.smtp.socketFactory.fallback", "false");
        props.put("mail.smtp.ssl.socketFactory", tls);
        props.put("mail.smtp.connectiontimeout", Long.toString(CONNECT_TIMEOUT.toMillis()));
        props.put("mail.smtp.timeout", Long.toString(READ_TIMEOUT.toMillis()));
        props.put("mail.smtp.writetimeout", Long.toString(WRITE_TIMEOUT.toMillis()));
        // Avoid a reverse-DNS lookup for the EHLO name inside the attempt budget.
        props.put("mail.smtp.localhost", "localhost");
        props.put("mail.smtp.starttls.enable", Boolean.toString(target.startTls()));
        props.put("mail.smtp.starttls.required", Boolean.toString(target.startTls()));
        this.session = Session.getInstance(props);
    }

    /** Starts the single independent watchdog. Scheduling never resets the deadline. */
    public void start(ScheduledExecutorService scheduler) {
        this.watchdogScheduler = scheduler;
        // No wall-clock recomputation and no clamp: a negative delay fires at once.
        long remainingNanos = deadlineNanos - System.nanoTime();
        this.watchdogTask = scheduler.schedule(this::abort, remainingNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Deadline action. Closes owned sockets directly; never touches the
     * synchronized SMTPTransport monitor and never sends QUIT.
     */
    public void abort() {
        if (aborted.compareAndSet(false, true)) {
            registry.closeAll();
            sender.interrupt(); // only to unblock setup/resolution, never proof of transport stop
        }
    }

    public boolean isAborted() {
        return aborted.get();
    }

    public boolean pastDeadline() {
        return System.nanoTime() >= deadlineNanos;
    }

    public EmailDeliveryService.Claim claim() {
        return claim;
    }

    public Duration deadline() {
        return deadline;
    }

    public Session session() {
        return session;
    }

    public SocketRegistry registry() {
        return registry;
    }

    /** Cancels the timer and releases every owned socket. Safe to call repeatedly. */
    public void close() {
        if (watchdogTask != null) {
            watchdogTask.cancel(false);
            if (watchdogScheduler instanceof ScheduledThreadPoolExecutor pool) {
                pool.remove((Runnable) watchdogTask); // drop the cancelled timer from the queue
            }
        }
        registry.closeAll();
    }

    static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing
        }
    }

    /** Set of sockets created for this attempt. Registrations after cancel are rejected. */
    public static final class SocketRegistry {
        private final Object lock = new Object();
        private final Set<Socket> sockets = new HashSet<>();
        private final AtomicBoolean aborted;
        private boolean closed;

        SocketRegistry() {
            this(new AtomicBoolean());
        }

        SocketRegistry(AtomicBoolean aborted) {
            this.aborted = aborted;
        }

        public void register(Socket socket) throws IOException {
            synchronized (lock) {
                if (closed || aborted.get()) {
                    closeQuietly(socket);
                    throw new IOException("attempt aborted before socket registration");
                }
                sockets.add(socket);
            }
        }

        public void closeAll() {
            List<Socket> copy;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                copy = new ArrayList<>(sockets);
                sockets.clear();
            }
            for (Socket socket : copy) {
                closeQuietly(socket);
            }
        }

        public int size() {
            synchronized (lock) {
                return sockets.size();
            }
        }

        public boolean isClosed() {
            synchronized (lock) {
                return closed;
            }
        }
    }
}
