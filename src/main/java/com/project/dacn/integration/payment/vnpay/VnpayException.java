package com.project.dacn.integration.payment.vnpay;

public class VnpayException extends RuntimeException {
    public VnpayException(String message) {
        super(message);
    }

    public VnpayException(String message, Throwable cause) {
        super(message, cause);
    }
}
