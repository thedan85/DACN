package com.project.dacn.common.exception;

public class VietQrNotConfiguredException extends RuntimeException {
    public VietQrNotConfiguredException(String property) {
        super("VietQR is not configured: " + property);
    }
}
