package com.project.dacn.integration.mail;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Supplier;

/**
 * Angus socket-factory hook. Angus calls the no-arg {@link #createSocket()}
 * before it connects, so the raw socket is registered before any blocking I/O.
 * The fallback property is disabled by {@link SmtpAttempt}, so an unregistered
 * socket is never substituted.
 */
public final class AttemptSocketFactory extends SocketFactory {

    private final SmtpAttempt.SocketRegistry registry;
    private final Supplier<Socket> socketSupplier;

    public AttemptSocketFactory(SmtpAttempt.SocketRegistry registry) {
        this(registry, Socket::new);
    }

    AttemptSocketFactory(SmtpAttempt.SocketRegistry registry, Supplier<Socket> socketSupplier) {
        this.registry = registry;
        this.socketSupplier = socketSupplier;
    }

    @Override
    public Socket createSocket() throws IOException {
        return owned(socketSupplier.get());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connect(owned(socketSupplier.get()), new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connect(owned(socketSupplier.get()), new InetSocketAddress(host, port),
                new InetSocketAddress(localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connect(owned(socketSupplier.get()), new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return connect(owned(socketSupplier.get()), new InetSocketAddress(address, port),
                new InetSocketAddress(localAddress, localPort));
    }

    private Socket owned(Socket socket) throws IOException {
        try {
            registry.register(socket);
        } catch (IOException e) {
            SmtpAttempt.closeQuietly(socket);
            throw e;
        }
        return socket;
    }

    private static Socket connect(Socket socket, InetSocketAddress remote, InetSocketAddress local) throws IOException {
        try {
            if (local != null) {
                socket.bind(local);
            }
            socket.connect(remote);
            return socket;
        } catch (IOException e) {
            SmtpAttempt.closeQuietly(socket);
            throw e;
        }
    }

    /** STARTTLS hook: keeps the wrapped socket owned by the same attempt. */
    public static final class Tls extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final SmtpAttempt.SocketRegistry registry;

        public Tls(SSLSocketFactory delegate, SmtpAttempt.SocketRegistry registry) {
            this.delegate = delegate;
            this.registry = registry;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return owned(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override public Socket createSocket(String host, int port) throws IOException {
            return owned(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return owned(delegate.createSocket(host, port, localHost, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return owned(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return owned(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket owned(Socket socket) throws IOException {
            try {
                registry.register(socket);
            } catch (IOException e) {
                SmtpAttempt.closeQuietly(socket);
                throw e;
            }
            return socket;
        }
    }
}
