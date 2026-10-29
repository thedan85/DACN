package com.project.dacn.common.exception;

public class GoogleLoginNotAllowedException extends RuntimeException {
    public GoogleLoginNotAllowedException(String message) {
        super(message);
    }
}
