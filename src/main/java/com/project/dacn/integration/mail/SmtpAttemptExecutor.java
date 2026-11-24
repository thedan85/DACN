package com.project.dacn.integration.mail;

import com.project.dacn.service.engagement.EmailDeliveryService;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.LongConsumer;

/**
 * Executes one attempt inside a single absolute deadline. The watchdog is
 * scheduled before the slot wait and never reset; the slot is released only
 * after the sender has finished and owned cleanup ran.
 */
public final class SmtpAttemptExecutor {

    public record Target(String host, int port, String user, String password, boolean startTls) {
    }

    @FunctionalInterface
    public interface MessageFactory {
        MimeMessage create(jakarta.mail.Session session) throws MessagingException;
    }

    private final Target target;
    private final ScheduledExecutorService watchdogScheduler;
    private final Semaphore sendingSlot;
    private final Duration deadline;
    private final SmtpHostResolver resolver;
    private final LongConsumer acceptanceSink;

    public SmtpAttemptExecutor(Target target, ScheduledExecutorService watchdogScheduler,
                               Semaphore sendingSlot) {
        this(target, watchdogScheduler, sendingSlot, SmtpAttempt.ATTEMPT_DEADLINE);
    }

    SmtpAttemptExecutor(Target target, ScheduledExecutorService watchdogScheduler,
                        Semaphore sendingSlot, Duration deadline) {
        this(target, watchdogScheduler, sendingSlot, deadline,
                new SmtpHostResolver(), ignored -> { });
    }

    public SmtpAttemptExecutor(Target target, ScheduledExecutorService watchdogScheduler,
                               Semaphore sendingSlot, Duration deadline,
                               SmtpHostResolver resolver, LongConsumer acceptanceSink) {
        this.target = target;
        this.watchdogScheduler = watchdogScheduler;
        this.sendingSlot = sendingSlot;
        this.deadline = deadline;
        this.resolver = resolver;
        this.acceptanceSink = acceptanceSink;
    }

    public SmtpAttemptResult execute(EmailDeliveryService.Claim claim, MessageFactory factory) {
        // Only the app-side monotonic anchor defines the budget. No DB/JVM clock
        // comparison: an elapsed budget returns UNKNOWN before any connection.
        long deadlineNanos = claim.claimedAtNanos() + deadline.toNanos();
        if (System.nanoTime() >= deadlineNanos) {
            return SmtpAttemptResult.unknown("CLAIM_BUDGET_ELAPSED");
        }

        SmtpAttempt attempt = new SmtpAttempt(claim, Thread.currentThread(), deadline, target);
        attempt.start(watchdogScheduler); // queue wait consumes the same deadline

        boolean acquired = false;
        try {
            sendingSlot.acquire();
            acquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            attempt.close();
            return SmtpAttemptResult.unknown("SLOT_WAIT_INTERRUPTED");
        }

        boolean acceptanceObserved = false;
        boolean connected = false;
        try {
            if (attempt.isAborted() || attempt.pastDeadline()) {
                return SmtpAttemptResult.unknown("SMTP_DEADLINE");
            }
            // Resolution runs on one daemon thread under a 5s cap inside the
            // remaining monotonic budget. The sender never calls JavaMail/Angus
            // with an unresolved host, so no non-interruptible lookup can hold
            // the slot. No socket exists until a resolved address is in hand.
            long remainingNanos = deadlineNanos - System.nanoTime();
            Duration resolverWait = remainingNanos <= 0 ? Duration.ZERO
                    : Duration.ofNanos(Math.min(SmtpHostResolver.LOOKUP_TIMEOUT.toNanos(), remainingNanos));
            SmtpHostResolver.Resolution resolution =
                    resolver.resolve(target.host(), resolverWait, attempt::isAborted);
            if (resolution.kind() == SmtpHostResolver.Kind.ABORTED) {
                return SmtpAttemptResult.unknown("SMTP_DEADLINE");
            }
            if (attempt.isAborted() || attempt.pastDeadline()) {
                return SmtpAttemptResult.unknown("SMTP_DEADLINE");
            }
            if (resolution.kind() == SmtpHostResolver.Kind.TIMEOUT) {
                // Bounded and distinct: no socket was ever created.
                return SmtpAttemptResult.unknown("SMTP_DNS_TIMEOUT");
            }
            if (resolution.kind() == SmtpHostResolver.Kind.FAILURE) {
                return SmtpAttemptResult.preAcceptanceFailure(resolution.failureCode());
            }

            MimeMessage message = factory.create(attempt.session());
            if (message == null || attempt.isAborted()) {
                return SmtpAttemptResult.unknown("SMTP_DEADLINE");
            }

            Transport transport = attempt.session().getTransport("smtp");
            try {
                // Connect by resolved IP; TLS identity/SNI keeps the original host.
                transport.connect(resolution.address().getHostAddress(),
                        target.port(), target.user(), target.password());
                connected = true;
                if (attempt.isAborted()) {
                    return SmtpAttemptResult.unknown("SMTP_DEADLINE");
                }
                transport.sendMessage(message, message.getAllRecipients());
                acceptanceObserved = true;
                acceptanceSink.accept(System.currentTimeMillis());
            } finally {
                try {
                    if (transport.isConnected()) {
                        transport.close(); // QUIT failure after acceptance never downgrades SENT
                    }
                } catch (Exception ignored) {
                    // The deadline closes the owned socket; no graceful QUIT is required.
                }
            }
            return SmtpAttemptResult.accepted();
        } catch (Exception e) {
            return classifyFailure(e, connected, attempt.isAborted(),
                    attempt.pastDeadline(), acceptanceObserved);
        } finally {
            attempt.close();
            Thread.interrupted(); // do not leak the watchdog's interrupt to the worker
            if (acquired) {
                sendingSlot.release();
            }
        }
    }

    /**
     * Conservative classification. Only a proven pre-acceptance failure may be
     * retried. Once connected, an I/O/protocol failure may have followed a
     * server 250 that we never observed (e.g. reset after DATA), so it is UNKNOWN.
     */
    static SmtpAttemptResult classifyFailure(Exception e, boolean connected,
                                             boolean aborted, boolean deadlinePassed,
                                             boolean acceptanceObserved) {
        if (acceptanceObserved) {
            return SmtpAttemptResult.accepted();
        }
        if (aborted || deadlinePassed) {
            return SmtpAttemptResult.unknown("SMTP_DEADLINE");
        }
        if (!connected || e instanceof SendFailedException) {
            // Connect/DNS failure before a socket existed, invalid recipients,
            // or an explicit SMTP rejection code on MAIL/RCPT/DATA.
            return SmtpAttemptResult.preAcceptanceFailure(classify(e));
        }
        return SmtpAttemptResult.unknown("SMTP_ACCEPTANCE_AMBIGUOUS");
    }

    private static String classify(Exception e) {
        if (e instanceof SocketTimeoutException) {
            return "SMTP_TIMEOUT";
        }
        if (e instanceof ConnectException) {
            return "SMTP_CONNECT_FAILED";
        }
        return "SMTP_PRE_ACCEPTANCE";
    }
}
