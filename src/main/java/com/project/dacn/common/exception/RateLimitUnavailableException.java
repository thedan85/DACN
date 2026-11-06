package com.project.dacn.common.exception;

public final class RateLimitUnavailableException extends RuntimeException {

    public RateLimitUnavailableException() {
        super("rate limiter unavailable");
    }
}
