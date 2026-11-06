package com.project.dacn.messaging.outbox;

/**
 * A bounded codec failure. The message is the reason code only; it never
 * contains the payload or the underlying Jackson message.
 */
public class OrderEventCodecException extends RuntimeException {

    public enum ReasonCode {
        EVENT_INVALID, EVENT_TOO_LARGE, EVENT_UNSUPPORTED
    }

    private final ReasonCode reasonCode;

    public OrderEventCodecException(ReasonCode reasonCode) {
        super(reasonCode.name());
        this.reasonCode = reasonCode;
    }

    public ReasonCode reasonCode() {
        return reasonCode;
    }
}
