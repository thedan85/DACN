package com.project.dacn.integration.mail;

import com.project.dacn.service.engagement.EmailDeliveryService;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;

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

    public SmtpAttemptExecutor(Target target, ScheduledExecutorService watchdogScheduler,
                               Semaphore sendingSlot) {
        this(target, watchdogScheduler, sendingSlot, SmtpAttempt.ATTEMPT_DEADLINE);
    }

    SmtpAttemptExecutor(Target target, ScheduledExecutorService watchdogScheduler,
                        Semaphore sendingSlot, Duration deadline) {
        this.target = target;
        this.watchdogScheduler = watchdogScheduler;
        this.sendingSlot = sendingSlot;
        this.deadline = deadline;
    }

    public SmtpAttemptResult execute(EmailDeliveryService.Claim claim, MessageFactory factory) {
        Instant now = Instant.now();
        Instant commitAt = claim.leaseExpiresAt().minus(EmailDeliveryService.LEASE);
        Instant deadlineWall = commitAt.plus(deadline);
        // Stale ownership or a budget already elapsed: never open a late connection.
        if (!now.isBefore(deadlineWall) || !now.isBefore(claim.leaseExpiresAt())) {
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
        try {
            if (attempt.isAborted() || attempt.pastDeadline()) {
                return SmtpAttemptResult.unknown("SMTP_DEADLINE");
            }
            MimeMessage message = factory.create(attempt.session()); // setup inside the budget
            if (message == null || attempt.isAborted()) {
                return SmtpAttemptResult.unknown("SMTP_DEADLINE");
            }

            Transport transport = attempt.session().getTransport("smtp");
            try {
                transport.connect(target.host(), target.port(), target.user(), target.password());
                if (attempt.isAborted()) {
                    return SmtpAttemptResult.unknown("SMTP_DEADLINE");
                }
                transport.sendMessage(message, message.getAllRecipients());
                acceptanceObserved = true;
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
            if (acceptanceObserved) {
                return SmtpAttemptResult.accepted();
            }
            if (attempt.isAborted() || attempt.pastDeadline()) {
                return SmtpAttemptResult.unknown("SMTP_DEADLINE");
            }
            return SmtpAttemptResult.preAcceptanceFailure(classify(e));
        } finally {
            attempt.close();
            Thread.interrupted(); // do not leak the watchdog's interrupt to the worker
            if (acquired) {
                sendingSlot.release();
            }
        }
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
