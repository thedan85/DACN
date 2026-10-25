package com.project.dacn.common.exception;

/**
 * The requested order transition is not allowed from the order's current
 * state (e.g. accepting an order that was already cancelled).
 */
public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}
