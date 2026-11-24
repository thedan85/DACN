package com.project.dacn.common.exception;

public class ForecastNotAvailableException extends RuntimeException {
    public ForecastNotAvailableException() {
        super("Revenue forecast is not available.");
    }
}
