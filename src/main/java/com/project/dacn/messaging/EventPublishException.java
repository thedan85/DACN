package com.project.dacn.messaging;

/**
 * A bounded publish failure. The message is the reason code only; it never
 * contains the payload or the broker exception text.
 */
public class EventPublishException extends RuntimeException {

    public enum ReasonCode {
        PUBLISH_NACK, PUBLISH_UNROUTABLE, PUBLISH_TIMEOUT, BROKER_UNAVAILABLE
    }

    private final ReasonCode code;

    public EventPublishException(ReasonCode code) {
        super(code.name());
        this.code = code;
    }

    public ReasonCode code() {
        return code;
    }
}
