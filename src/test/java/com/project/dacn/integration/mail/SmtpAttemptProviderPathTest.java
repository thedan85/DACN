package com.project.dacn.integration.mail;

import com.project.dacn.integration.mail.SmtpAttemptExecutor.Target;
import com.project.dacn.integration.mail.SmtpAttemptResult.Outcome;
import com.project.dacn.service.engagement.EmailDeliveryService;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider-path tests: raw/TLS registration, limits, fallback and stale timers. */
class SmtpAttemptProviderPathTest {

    private static final Target TARGET = new Target("127.0.0.1", 2525, null, null, false);

    @Test
    void rawSocketRegisteredBeforeBlockingConnect_andLateRegistrationRejected() throws Exception {
        SmtpAttempt.SocketRegistry registry = new SmtpAttempt.SocketRegistry();
        CountDownLatch connectEntered = new CountDownLatch(1);
        AtomicReference<Thread> connectorRef = new AtomicReference<>();
        Socket blocking = new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                connectorRef.set(Thread.currentThread());
                connectEntered.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("connect interrupted by socket close", e);
                }
            }

            @Override
            public synchronized void close() throws IOException {
                Thread connector = connectorRef.get();
                if (connector != null) {
                    connector.interrupt();
                }
                super.close();
            }
        };
        AttemptSocketFactory factory = new AttemptSocketFactory(registry, () -> blocking);
        Socket socket = factory.createSocket();
        assertThat(socket).isSameAs(blocking);
        assertThat(registry.size()).isEqualTo(1); // registered before any blocking I/O

        AtomicBoolean connectFailed = new AtomicBoolean();
        Thread connector = new Thread(() -> {
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", 1), 5_000);
            } catch (IOException e) {
                connectFailed.set(true);
            }
        }, "stalled-connect");
        connector.start();
        assertThat(connectEntered.await(2, TimeUnit.SECONDS)).isTrue();

        registry.closeAll();
        connector.join(3_000);
        assertThat(connectFailed).isTrue();
        assertThat(socket.isClosed()).isTrue();
        assertThat(registry.isClosed()).isTrue();
        assertThatThrownBy(factory::createSocket).isInstanceOf(IOException.class);
        assertThat(registry.size()).isZero();
    }

    @Test
    void tlsWrappedSocketStaysOwnedThroughWrapping() throws Exception {
        SmtpAttempt.SocketRegistry registry = new SmtpAttempt.SocketRegistry();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket raw = new Socket(listener.getInetAddress(), listener.getLocalPort());
            Socket peer = listener.accept();
            registry.register(raw);

            SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
            AttemptSocketFactory.Tls tls = new AttemptSocketFactory.Tls(delegate, registry);
            Socket wrapped = tls.createSocket(raw, "127.0.0.1", listener.getLocalPort(), true);
            assertThat(registry.size()).isEqualTo(2);

            registry.closeAll();
            assertThat(raw.isClosed()).isTrue();
            assertThat(wrapped.isClosed()).isTrue();
            peer.close();
        }
    }

    @Test
    void providerLimitsAndNoFallbackAreConfigured() {
        SmtpAttempt attempt = new SmtpAttempt(freshClaim(), Thread.currentThread(),
                Duration.ofSeconds(25), TARGET);
        Session session = attempt.session();

        assertThat(session.getProperty("mail.smtp.socketFactory.fallback")).isEqualTo("false");
        assertThat(session.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
        assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("10000");
        assertThat(session.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
        assertThat(session.getProperties().get("mail.smtp.socketFactory"))
                .isInstanceOf(AttemptSocketFactory.class);
        assertThat(session.getProperties().get("mail.smtp.ssl.socketFactory"))
                .isInstanceOf(AttemptSocketFactory.Tls.class);
    }

    @Test
    void staleWatchdogCannotCloseAnotherAttemptsSockets() throws Exception {
        SmtpAttempt expired = new SmtpAttempt(freshClaim(), Thread.currentThread(),
                Duration.ofSeconds(25), TARGET);
        SmtpAttempt current = new SmtpAttempt(freshClaim(), Thread.currentThread(),
                Duration.ofSeconds(25), TARGET);
        Socket fresh = new Socket();
        current.registry().register(fresh);

        expired.abort();

        assertThat(expired.isAborted()).isTrue();
        assertThat(current.isAborted()).isFalse();
        assertThat(fresh.isClosed()).isFalse();
        assertThat(current.registry().size()).isEqualTo(1);
        current.close();
        assertThat(fresh.isClosed()).isTrue();
    }

    @Test
    void postConnectFailureIsUnknownUnlessProvenPreAcceptance() {
        IOException reset = new IOException("reset after body handoff");

        // A reset after connection is ambiguous, never retry-safe.
        assertThat(SmtpAttemptExecutor.classifyFailure(reset, true, false, false, false).outcome())
                .isEqualTo(Outcome.UNKNOWN);
        // Proven pre-acceptance categories stay retryable.
        assertThat(SmtpAttemptExecutor.classifyFailure(
                new java.net.ConnectException("refused"), false, false, false, false).outcome())
                .isEqualTo(Outcome.PRE_ACCEPTANCE_FAILURE);
        assertThat(SmtpAttemptExecutor.classifyFailure(
                new SendFailedException("invalid recipient"), true, false, false, false).outcome())
                .isEqualTo(Outcome.PRE_ACCEPTANCE_FAILURE);
        assertThat(SmtpAttemptExecutor.classifyFailure(
                new SMTPSendFailedException("DATA", 550, "rejected", null, null, null, null),
                true, false, false, false).outcome())
                .isEqualTo(Outcome.PRE_ACCEPTANCE_FAILURE);
        // Deadline and observed acceptance still dominate.
        assertThat(SmtpAttemptExecutor.classifyFailure(reset, true, true, false, false).outcome())
                .isEqualTo(Outcome.UNKNOWN);
        assertThat(SmtpAttemptExecutor.classifyFailure(reset, true, false, false, true).outcome())
                .isEqualTo(Outcome.ACCEPTED);
    }

    private static EmailDeliveryService.Claim freshClaim() {
        Instant commit = Instant.now();
        return new EmailDeliveryService.Claim(
                9L, UUID.randomUUID(), 42L, UUID.randomUUID(),
                commit.plus(EmailDeliveryService.LEASE), 2, 9L, System.nanoTime());
    }
}
