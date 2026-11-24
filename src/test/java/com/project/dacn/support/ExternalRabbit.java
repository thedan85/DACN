package com.project.dacn.support;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.opentest4j.TestAbortedException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Parses the native RabbitMQ URI from {@code TEST_RABBITMQ_URL} and builds a
 * connection factory for it. The URI is never logged.
 */
public final class ExternalRabbit {

    private static final String URL_ENV = "TEST_RABBITMQ_URL";
    private static final int AMQP_PORT = 5672;
    private static final int AMQPS_PORT = 5671;
    private static final String DACN_PREFIX = "dacn.";

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;
    private final boolean ssl;

    private ExternalRabbit(String host, int port, String username, String password,
                           String virtualHost, boolean ssl) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.virtualHost = virtualHost;
        this.ssl = ssl;
    }

    public static ExternalRabbit fromEnvironment() {
        String url = System.getenv(URL_ENV);
        if (url == null || url.isBlank()) {
            throw new TestAbortedException(
                    URL_ENV + " is not configured; skipping external RabbitMQ tests");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(URL_ENV + " is not a valid URI");
        }

        boolean ssl = "amqps".equalsIgnoreCase(uri.getScheme());
        if (!ssl && !"amqp".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException(URL_ENV + " must use the amqp or amqps scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException(URL_ENV + " must include a host");
        }

        String username = "guest";
        String password = "guest";
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo != null) {
            int separator = rawUserInfo.indexOf(':');
            username = decode(separator >= 0 ? rawUserInfo.substring(0, separator) : rawUserInfo);
            password = separator >= 0 ? decode(rawUserInfo.substring(separator + 1)) : "";
        }

        String rawPath = uri.getRawPath();
        String virtualHost = rawPath == null || rawPath.isEmpty() || rawPath.equals("/")
                ? "/"
                : decode(rawPath.substring(1));

        return new ExternalRabbit(uri.getHost(),
                uri.getPort() > 0 ? uri.getPort() : (ssl ? AMQPS_PORT : AMQP_PORT),
                username, password, virtualHost, ssl);
    }

    public CachingConnectionFactory connectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setVirtualHost(virtualHost);
        if (ssl) {
            try {
                factory.getRabbitConnectionFactory().useSslProtocol();
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new IllegalStateException("RabbitMQ TLS configuration failed");
            }
        }
        return factory;
    }

    /**
     * Declares exchanges first, then queues, then bindings, so a binding never
     * references a queue that does not exist yet. The target must be empty and
     * exclusively reserved for this sequential invocation.
     */
    public static OwnedTopology declare(RabbitAdmin admin, Collection<Declarable> declarations) {
        List<Exchange> exchanges = declarations.stream().filter(Exchange.class::isInstance)
                .map(Exchange.class::cast).toList();
        List<Queue> queues = declarations.stream().filter(Queue.class::isInstance)
                .map(Queue.class::cast).toList();
        for (Exchange exchange : exchanges) {
            requireDacnName(exchange.getName());
            requireAbsent(admin, exchange.getName(), false);
        }
        for (Queue queue : queues) {
            requireDacnName(queue.getName());
            requireAbsent(admin, queue.getName(), true);
        }

        OwnedTopology owned = new OwnedTopology(admin);
        try {
            for (Exchange exchange : exchanges) {
                admin.declareExchange(exchange);
                owned.exchangeNames.add(exchange.getName());
            }
            for (Queue queue : queues) {
                if (!queue.getName().equals(admin.declareQueue(queue))) {
                    throw new IllegalStateException("RabbitMQ queue creation was not confirmed");
                }
                owned.queueNames.add(queue.getName());
            }
            declarations.stream().filter(Binding.class::isInstance).map(Binding.class::cast)
                    .forEach(admin::declareBinding);
            return owned;
        } catch (RuntimeException | Error failure) {
            owned.close();
            throw failure;
        }
    }

    private static void requireDacnName(String name) {
        if (!name.startsWith(DACN_PREFIX)) {
            throw new IllegalArgumentException("Broker fixtures require named dacn.* targets");
        }
    }

    private static void requireAbsent(RabbitAdmin admin, String name, boolean queue) {
        Boolean exists = admin.getRabbitTemplate().execute(channel -> {
            try {
                if (queue) {
                    channel.queueDeclarePassive(name);
                } else {
                    channel.exchangeDeclarePassive(name);
                }
                return true;
            } catch (IOException | ShutdownSignalException failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof ShutdownSignalException shutdown
                            && shutdown.getReason() instanceof AMQP.Channel.Close close
                            && close.getReplyCode() == 404) {
                        return false;
                    }
                }
                throw failure;
            }
        });
        if (!Boolean.FALSE.equals(exists)) {
            throw new IllegalStateException("Refusing pre-existing RabbitMQ fixture target: " + name);
        }
    }

    public static final class OwnedTopology implements AutoCloseable {

        private final RabbitAdmin admin;
        private final List<String> queueNames = new ArrayList<>();
        private final List<String> exchangeNames = new ArrayList<>();
        private boolean closed;

        private OwnedTopology(RabbitAdmin admin) {
            this.admin = admin;
        }

        public void purgeQueues() {
            if (closed) {
                throw new IllegalStateException("RabbitMQ fixture ownership is closed");
            }
            queueNames.forEach(admin::purgeQueue);
        }

        /**
         * Deletes only recorded invocation-owned creations. Queues go first so
         * their bindings disappear with them; missing objects are ignored.
         */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                queueNames.forEach(name -> deleteQueue(admin, name));
                exchangeNames.forEach(name -> deleteExchange(admin, name));
            }
        }
    }

    private static void deleteQueue(RabbitAdmin admin, String name) {
        try {
            admin.deleteQueue(name);
        } catch (AmqpException e) {
            // The owned queue is already gone or unavailable; cleanup is best effort.
        }
    }

    private static void deleteExchange(RabbitAdmin admin, String name) {
        try {
            admin.deleteExchange(name);
        } catch (AmqpException e) {
            // The owned exchange is already gone or unavailable.
        }
    }

    private static String decode(String value) {
        // URLDecoder treats '+' as a space; a literal '+' in a path is percent-encoded.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
