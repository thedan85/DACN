package com.project.dacn.common.exception;

/**
 * Raised when an authenticated admin actor tries to perform a user or role
 * mutation that exceeds its own authority.
 *
 * <p>This is a 403, not a business-rule 409: the request is understood and the
 * resource exists, but the actor is not allowed to perform the change.
 */
public class AdminAuthorityException extends RuntimeException {

    public static final String MESSAGE_KEY = "error.admin.authority";

    public AdminAuthorityException() {
        super(MESSAGE_KEY);
    }
}
