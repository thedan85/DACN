package com.project.dacn.integration.mail;

import com.project.dacn.integration.mail.SmtpAttemptExecutor.MessageFactory;
import com.project.dacn.integration.mail.SmtpAttemptExecutor.Target;
import com.project.dacn.integration.mail.SmtpAttemptResult.Outcome;
import com.project.dacn.service.engagement.EmailDeliveryService;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Loopback fault tests for the whole-attempt SMTP deadline. */
class SmtpAttemptDeadlineTest {

    private ScheduledThreadPoolExecutor watchdog;
    private Semaphore slot;
    private SmtpAttemptExecutor executor;
    private FakeSmtpServer server;

    private void setUp(Duration deadline, boolean startTls) throws Exception {
        server = new FakeSmtpServer();
        server.advertiseStartTls = startTls;
        watchdog = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "test-watchdog");
            t.setDaemon(true);
            return t;
        });
        slot = new Semaphore(1);
        Target target = new Target("127.0.0.1", server.port(), null, null, startTls);
        executor = deadline == null
                ? new SmtpAttemptExecutor(target, watchdog, slot)
                : new SmtpAttemptExecutor(target, watchdog, slot, deadline);
    }

    @AfterEach
    void tearDown() {
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void sevenDelayedReplies_abortAtDeadline_noLateTransmission() throws Exception {
        setUp(null, false); // production default: 25s
        server.replyDelayMs = 9_000; // seven replies x 9s would take 63s

        long started = System.currentTimeMillis();
        SmtpAttemptResult result = executor.execute(freshClaim(), message("subject", "body"));
        long elapsed = System.currentTimeMillis() - started;

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(elapsed).isGreaterThanOrEqualTo(23_000).isLessThan(30_000);
        assertThat(server.awaitDisconnect(3_000)).isTrue();
        long observed = server.disconnectedAtMillis() - server.acceptedAtMillis();
        System.out.println("TASK9_EVIDENCE sevenReplies senderElapsedMs=" + elapsed
                + " serverObservedDisconnectMs=" + observed
                + " messageReceived=" + server.messageReceived()
                + " connections=" + server.connectionCount());
        assertThat(observed).isGreaterThanOrEqualTo(23_000).isLessThan(30_000);
        assertThat(server.messageReceived()).isFalse();
        assertThat(server.connectionCount()).isEqualTo(1);
        assertThat(server.awaitFirstConnection(1_000)).isTrue();

        // No late transmission after the caller returned.
        Thread.sleep(1_000);
        assertThat(server.messageReceived()).isFalse();
        assertThat(server.connectionCount()).isEqualTo(1);
        assertThat(slot.availablePermits()).isEqualTo(1);
        assertThat(watchdog.getQueue()).isEmpty();
    }

    @Test
    void stalledConnect_rawSocketCloseStopsSend() throws Exception {
        setUp(Duration.ofSeconds(2), false);
        server.stallGreeting = true;
        SmtpAttemptResult result = executor.execute(freshClaim(), message("subject", "body"));

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(server.awaitDisconnect(3_000)).isTrue();
        assertThat(server.messageReceived()).isFalse();
        assertThat(server.connectionCount()).isEqualTo(1);
        assertThat(slot.availablePermits()).isEqualTo(1);
    }

    @Test
    void stalledTls_handshakeStoppedAtDeadline() throws Exception {
        setUp(Duration.ofSeconds(2), true);
        server.stallAfterStartTls = true;
        SmtpAttemptResult result = executor.execute(freshClaim(), message("subject", "body"));

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(server.awaitDisconnect(3_000)).isTrue();
        assertThat(server.messageReceived()).isFalse();
        assertThat(slot.availablePermits()).isEqualTo(1);
        assertThat(watchdog.getQueue()).isEmpty();
    }

    @Test
    void stalledBody_writeStoppedAtDeadline() throws Exception {
        setUp(Duration.ofSeconds(2), false);
        server.stallAfterData = true; // no 354, so the body transfer cannot proceed
        SmtpAttemptResult result = executor.execute(freshClaim(), message("large", "x".repeat(4096)));

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(server.awaitDisconnect(3_000)).isTrue();
        assertThat(server.messageReceived()).isFalse();
        assertThat(slot.availablePermits()).isEqualTo(1);
    }

    @Test
    void stalledFinalReply_unknownEvenThoughBodyArrived() throws Exception {
        setUp(Duration.ofSeconds(2), false);
        server.stallFinalReply = true;
        SmtpAttemptResult result = executor.execute(freshClaim(), message("subject", "body"));

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(server.messageReceived()).isTrue(); // body sent, acceptance unobserved
        assertThat(server.awaitDisconnect(3_000)).isTrue();
        assertThat(slot.availablePermits()).isEqualTo(1);
    }

    @Test
    void closeAfterAcceptance_stillAccepted() throws Exception {
        setUp(Duration.ofSeconds(2), false);
        server.stallQuitReply = true;
        SmtpAttemptResult result = executor.execute(freshClaim(), message("subject", "body"));

        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(server.messageReceived()).isTrue();
        assertThat(server.awaitDisconnect(3_000)).isTrue();
        assertThat(slot.availablePermits()).isEqualTo(1);
        assertThat(watchdog.getQueue()).isEmpty();
    }

    @Test
    void normalSend_acceptsAndLeavesNoTimerOrSender() throws Exception {
        setUp(Duration.ofSeconds(5), false);
        SmtpAttemptResult result = executor.execute(freshClaim(), message("subject", "body"));

        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(server.messageReceived()).isTrue();
        assertThat(server.connectionCount()).isEqualTo(1);
        assertThat(slot.availablePermits()).isEqualTo(1);
        assertThat(watchdog.getQueue()).isEmpty();
    }

    @Test
    void slotReusedOnlyAfterTermination() throws Exception {
        setUp(Duration.ofSeconds(2), false);
        server.stallGreeting = true;
        // Second attempt has a longer own budget, so its queue wait does not consume it all.
        SmtpAttemptExecutor secondExecutor = new SmtpAttemptExecutor(
                new Target("127.0.0.1", server.port(), null, null, false),
                watchdog, slot, Duration.ofSeconds(8));
        CountDownLatch firstReturned = new CountDownLatch(1);
        AtomicReference<SmtpAttemptResult> second = new AtomicReference<>();
        CountDownLatch secondReturned = new CountDownLatch(1);

        Thread first = new Thread(() -> {
            executor.execute(freshClaim(), message("first", "body"));
            firstReturned.countDown();
        }, "first-send");
        first.start();
        assertThat(server.awaitFirstConnection(2_000)).isTrue();
        assertThat(slot.availablePermits()).isZero();

        Thread secondThread = new Thread(() -> {
            second.set(secondExecutor.execute(freshClaim(), message("second", "body")));
            secondReturned.countDown();
        }, "second-send");
        secondThread.start();
        assertThat(secondReturned.await(500, TimeUnit.MILLISECONDS)).isFalse(); // blocked on slot

        assertThat(firstReturned.await(8_000, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(secondReturned.await(8_000, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(second.get()).isNotNull();
        assertThat(server.acceptTimes()).hasSize(2);
        long waitUntil = System.currentTimeMillis() + 3_000;
        while (server.disconnectTimes().size() < 2 && System.currentTimeMillis() < waitUntil) {
            Thread.sleep(25);
        }
        assertThat(server.disconnectTimes()).hasSize(2);
        // The second socket may open only after the first one was torn down.
        assertThat(server.acceptTimes().get(1))
                .isGreaterThanOrEqualTo(server.disconnectTimes().get(0));
        assertThat(slot.availablePermits()).isEqualTo(1);
    }

    @Test
    void elapsedClaimBudget_opensNoConnection() throws Exception {
        setUp(Duration.ofSeconds(5), false);
        // Monotonic anchor 40s in the past: the 5s test budget is already gone.
        long staleNanos = System.nanoTime() - Duration.ofSeconds(40).toNanos();
        EmailDeliveryService.Claim stale = new EmailDeliveryService.Claim(
                1L, UUID.randomUUID(), 42L, UUID.randomUUID(),
                Instant.now().plus(EmailDeliveryService.LEASE), 1, 1L, staleNanos);

        SmtpAttemptResult result = executor.execute(stale, message("subject", "body"));

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(server.connectionCount()).isZero();
        assertThat(slot.availablePermits()).isEqualTo(1);
        assertThat(watchdog.getQueue()).isEmpty();
    }

    @Test
    void setupDelay_consumesBudget_noLateConnection() throws Exception {
        setUp(Duration.ofSeconds(1), false);
        MessageFactory slowFactory = session -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new jakarta.mail.MessagingException("setup interrupted", e);
            }
            return message("subject", "body").create(session);
        };

        long started = System.currentTimeMillis();
        SmtpAttemptResult result = executor.execute(freshClaim(), slowFactory);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(server.connectionCount()).isZero();
        assertThat(elapsed).isLessThan(4_000);
        assertThat(slot.availablePermits()).isEqualTo(1);
    }

    private static EmailDeliveryService.Claim freshClaim() {
        Instant commit = Instant.now();
        return new EmailDeliveryService.Claim(
                1L, UUID.randomUUID(), 42L, UUID.randomUUID(),
                commit.plus(EmailDeliveryService.LEASE), 1, 1L, System.nanoTime());
    }

    private static MessageFactory message(String subject, String body) {
        return (Session session) -> {
            // Override Message-ID generation: the stock updateHeaders() does a
            // reverse-DNS lookup that would otherwise consume the attempt budget.
            MimeMessage message = new MimeMessage(session) {
                @Override
                protected void updateMessageID() {
                    try {
                        setHeader("Message-ID", "<task9-" + UUID.randomUUID() + "@example.test>");
                    } catch (jakarta.mail.MessagingException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            message.setFrom(new InternetAddress("sender@example.test"));
            message.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse("receiver@example.test"));
            message.setSubject(subject);
            message.setText(body);
            return message;
        };
    }
}
