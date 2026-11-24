package com.project.dacn.event;

/**
 * A bounded rejection of an event that violates the contract. The message
 * names the field only; it never echoes the offending value.
 */
public class OrderEventValidationException extends RuntimeException {

    public static final String CODE = "EVENT_INVALID";

    private final String field;

    public OrderEventValidationException(String field) {
        super("Invalid order event: " + field);
        this.field = field;
    }

    public String code() {
        return CODE;
    }

    public String field() {
        return field;
    }
}
