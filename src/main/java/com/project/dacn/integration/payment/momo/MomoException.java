package com.project.dacn.integration.payment.momo;

public class MomoException extends RuntimeException {
    public MomoException(String message) {
        super(message);
    }

    public MomoException(String message, Throwable cause) {
        super(message, cause);
    }
}
