package com.project.dacn.integration.realtime;

public class RealtimeConfigurationException extends RuntimeException {

    private final String code;

    public RealtimeConfigurationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RealtimeConfigurationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
