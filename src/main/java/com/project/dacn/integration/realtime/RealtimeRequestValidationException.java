package com.project.dacn.integration.realtime;

public class RealtimeRequestValidationException extends RuntimeException {

    public RealtimeRequestValidationException() {
        super("invalid realtime credential request");
    }
}
