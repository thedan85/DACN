package com.project.dacn.common.exception;

public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException() {
        super("Invalid or expired token");
    }
}
