package com.project.dacn.integration.mail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Small loopback SMTP peer that can stall or delay individual protocol replies. */
final class FakeSmtpServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final List<Socket> connections = new CopyOnWriteArrayList<>();
    private final List<Long> acceptTimes = new CopyOnWriteArrayList<>();
    private final List<Long> disconnectTimes = new CopyOnWriteArrayList<>();
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<String> commands = new LinkedBlockingQueue<>();
    private final AtomicBoolean acceptLoopRunning = new AtomicBoolean(true);
    private final AtomicBoolean messageReceived = new AtomicBoolean();
    private final AtomicBoolean clientDisconnected = new AtomicBoolean();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicLong acceptedAt = new AtomicLong();
    private final AtomicLong disconnectedAt = new AtomicLong();
    private final CountDownLatch firstConnection = new CountDownLatch(1);
    private final CountDownLatch disconnectLatch = new CountDownLatch(1);

    volatile long replyDelayMs;
    volatile boolean stallGreeting;
    volatile boolean advertiseStartTls;
    volatile boolean stallAfterStartTls;
    volatile boolean stallAfterData;
    volatile boolean stallFinalReply;
    volatile boolean stallQuitReply;

    FakeSmtpServer() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(this::acceptLoop, "fake-smtp-accept");
        accept.setDaemon(true);
        workers.add(accept);
        accept.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    boolean messageReceived() {
        return messageReceived.get();
    }

    boolean clientDisconnected() {
        return clientDisconnected.get();
    }

    int connectionCount() {
        return connectionCount.get();
    }

    List<Long> acceptTimes() {
        return acceptTimes;
    }

    List<Long> disconnectTimes() {
        return disconnectTimes;
    }

    long acceptedAtMillis() {
        return acceptedAt.get();
    }

    long disconnectedAtMillis() {
        return disconnectedAt.get();
    }

    boolean awaitFirstConnection(long millis) throws InterruptedException {
        return firstConnection.await(millis, TimeUnit.MILLISECONDS);
    }

    boolean awaitDisconnect(long millis) throws InterruptedException {
        return disconnectLatch.await(millis, TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
        while (acceptLoopRunning.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                connections.add(socket);
                connectionCount.incrementAndGet();
                long accepted = System.currentTimeMillis();
                acceptedAt.compareAndSet(0L, accepted);
                acceptTimes.add(accepted);
                firstConnection.countDown();
                Thread reader = new Thread(() -> readLoop(socket), "fake-smtp-read-" + connectionCount.get());
                Thread writer = new Thread(() -> writeLoop(socket), "fake-smtp-write-" + connectionCount.get());
                reader.setDaemon(true);
                writer.setDaemon(true);
                workers.add(reader);
                workers.add(writer);
                reader.start();
                writer.start();
            } catch (IOException e) {
                if (acceptLoopRunning.get()) {
                    throw new IllegalStateException("fake SMTP accept failed", e);
                }
            }
        }
    }

    private void readLoop(Socket socket) {
        boolean readingBody = false;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (readingBody && ".".equals(line)) {
                    messageReceived.set(true);
                    readingBody = false;
                    commands.offer("<DATA_END>");
                } else if ("DATA".equalsIgnoreCase(line)) {
                    readingBody = true;
                    commands.offer(line);
                } else if (!readingBody) {
                    // Body lines are not SMTP commands; never acknowledge them.
                    commands.offer(line);
                }
            }
        } catch (IOException ignored) {
            // Peer reset is an expected fault mode.
        } finally {
            long disconnected = System.currentTimeMillis();
            disconnectTimes.add(disconnected);
            if (clientDisconnected.compareAndSet(false, true)) {
                disconnectedAt.set(disconnected);
                disconnectLatch.countDown();
            }
            closeQuietly(socket);
        }
    }

    private void writeLoop(Socket socket) {
        try {
            reply(socket, stallGreeting ? null : "220 fake ESMTP ready\r\n");
            while (true) {
                String command = commands.poll(30, TimeUnit.SECONDS);
                if (command == null) {
                    return;
                }
                String upper = command.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                    String ehlo = advertiseStartTls
                            ? "250-fake\r\n250-STARTTLS\r\n250 SIZE 10485760\r\n"
                            : "250-fake\r\n250 SIZE 10485760\r\n";
                    reply(socket, ehlo);
                } else if (upper.startsWith("STARTTLS")) {
                    if (stallAfterStartTls) {
                        parkUntilClosed(socket);
                    } else {
                        reply(socket, "220 Ready to start TLS\r\n");
                    }
                } else if (upper.startsWith("MAIL FROM")) {
                    reply(socket, "250 OK\r\n");
                } else if (upper.startsWith("RCPT TO")) {
                    reply(socket, "250 OK\r\n");
                } else if (upper.startsWith("DATA")) {
                    if (stallAfterData) {
                        parkUntilClosed(socket); // no 354: body transfer phase stalls
                    } else {
                        reply(socket, "354 End data with <CRLF>.<CRLF>\r\n");
                    }
                } else if ("<DATA_END>".equals(command)) {
                    if (stallFinalReply) {
                        parkUntilClosed(socket);
                    } else {
                        reply(socket, "250 queued\r\n");
                    }
                } else if (upper.startsWith("QUIT")) {
                    if (stallQuitReply) {
                        parkUntilClosed(socket);
                    } else {
                        reply(socket, "221 Bye\r\n");
                    }
                    return;
                } else {
                    reply(socket, "250 OK\r\n");
                }
                if (socket.isClosed()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Peer closed.
        }
    }

    private void reply(Socket socket, String text) throws IOException, InterruptedException {
        if (text == null) {
            parkUntilClosed(socket);
            return;
        }
        if (replyDelayMs > 0) {
            Thread.sleep(replyDelayMs);
        }
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void parkUntilClosed(Socket socket) throws InterruptedException {
        while (!socket.isClosed()) {
            Thread.sleep(50);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing
        }
    }

    @Override
    public void close() {
        acceptLoopRunning.set(false);
        for (Socket socket : connections) {
            closeQuietly(socket);
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // nothing
        }
        for (Thread worker : workers) {
            worker.interrupt();
        }
    }
}
