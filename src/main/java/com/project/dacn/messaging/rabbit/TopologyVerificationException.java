package com.project.dacn.messaging.rabbit;

/**
 * Carries only a bounded reason code and an optional queue name. Response
 * bodies, URLs, credentials, and exception text never enter the message.
 */
public final class TopologyVerificationException extends RuntimeException {

    public enum Code {
        BROKER_VERSION,
        FEATURE_FLAG,
        QUEUE_POLICY,
        BINDING,
        VERIFICATION_TIMEOUT
    }

    private final Code code;
    private final String queueName;

    public TopologyVerificationException(Code code) {
        this(code, null);
    }

    public TopologyVerificationException(Code code, String queueName) {
        super(queueName == null
                ? "RabbitMQ topology verification failed: " + code
                : "RabbitMQ topology verification failed: " + code + " (" + queueName + ")");
        this.code = code;
        this.queueName = queueName;
    }

    public Code code() {
        return code;
    }

    public String queueName() {
        return queueName;
    }
}
