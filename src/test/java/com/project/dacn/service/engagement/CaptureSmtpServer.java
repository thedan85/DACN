package com.project.dacn.service.engagement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback SMTP capture/fault peer. No real recipient or provider is contacted. */
final class CaptureSmtpServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicBoolean messageReceived = new AtomicBoolean();
    private final AtomicInteger messagesReceived = new AtomicInteger();
    private volatile String lastRawMessage = "";
    private final CountDownLatch firstConnection = new CountDownLatch(1);
    private final AtomicBoolean running = new AtomicBoolean(true);

    volatile boolean rejectRecipient;
    volatile boolean stallFinalReply;
    volatile boolean dropAfterMailFrom;
    volatile boolean failQuit;
    volatile long delayGreetingMs;

    CaptureSmtpServer() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(this::acceptLoop, "capture-smtp-accept");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    int connectionCount() {
        return connections.get();
    }

    boolean messageReceived() {
        return messageReceived.get();
    }

    /** Number of completed DATA transfers observed by this capture peer. */
    int messageCount() {
        return messagesReceived.get();
    }

    /** Raw RFC-822 bytes of the most recent completed DATA transfer (CRLF joined). */
    String lastRawMessage() {
        return lastRawMessage;
    }

    boolean awaitFirstConnection(long millis) throws InterruptedException {
        return firstConnection.await(millis, TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                sockets.add(socket);
                connections.incrementAndGet();
                firstConnection.countDown();
                Thread worker = new Thread(() -> handle(socket), "capture-smtp-conn-" + connections.get());
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running.get()) {
                    throw new IllegalStateException("capture SMTP accept failed", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
            if (delayGreetingMs > 0) {
                Thread.sleep(delayGreetingMs);
            }
            write(socket, "220 capture ready\r\n");
            String line;
            while ((line = in.readLine()) != null) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                    write(socket, "250-capture\r\n250 SIZE 10485760\r\n");
                } else if (upper.startsWith("MAIL FROM")) {
                    write(socket, "250 OK\r\n");
                    if (dropAfterMailFrom) {
                        return;
                    }
                } else if (upper.startsWith("RCPT TO")) {
                    write(socket, rejectRecipient ? "550 5.1.1 rejected\r\n" : "250 OK\r\n");
                } else if (upper.startsWith("DATA")) {
                    write(socket, "354 End data with <CRLF>.<CRLF>\r\n");
                    StringBuilder raw = new StringBuilder();
                    while ((line = in.readLine()) != null && !".".equals(line)) {
                        // Captured in memory only (never logged); bounded to avoid unbounded growth.
                        if (raw.length() < 1_000_000) {
                            raw.append(line).append("\r\n");
                        }
                    }
                    lastRawMessage = raw.toString();
                    messagesReceived.incrementAndGet();
                    messageReceived.set(true);
                    if (stallFinalReply) {
                        parkUntilClosed(socket);
                        return;
                    }
                    write(socket, "250 queued\r\n");
                } else if (upper.startsWith("QUIT")) {
                    if (failQuit) {
                        Thread.sleep(200);
                        return; // abrupt close: the final acceptance was already observed
                    }
                    write(socket, "221 bye\r\n");
                    return;
                } else {
                    write(socket, "250 OK\r\n");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // peer reset is an expected fault mode
        }
    }

    private static void write(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void parkUntilClosed(Socket socket) throws InterruptedException {
        while (!socket.isClosed()) {
            Thread.sleep(25);
        }
    }

    @Override
    public void close() {
        running.set(false);
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing
            }
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // nothing
        }
    }
}
