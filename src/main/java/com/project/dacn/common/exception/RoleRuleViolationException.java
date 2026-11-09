package com.project.dacn.common.exception;

/**
 * A domain rule rejected an admin role mutation. The controller advice resolves
 * {@link #getMessageKey()} through {@code MessageSource} and returns HTTP 409.
 */
public class RoleRuleViolationException extends RuntimeException {

    private final String messageKey;
    private final Object[] args;

    public RoleRuleViolationException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getArgs() {
        return args;
    }
}
