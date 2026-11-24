package com.project.dacn.service.dashboard;

import com.project.dacn.common.exception.ForecastNotAvailableException;
import com.project.dacn.dto.response.dashboard.RevenueForecastResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

/** Reads the precomputed Prophet forecast produced by the Python pipeline. */
@Service
public class RevenueForecastService {

    private final String filePath;
    private final ObjectMapper mapper;

    public RevenueForecastService(
            @Value("${app.forecast.file}") String filePath,
            ObjectMapper mapper) {
        this.filePath = filePath;
        this.mapper = mapper;
    }

    public RevenueForecastResponse getForecast() {
        try {
            String json = Files.readString(Path.of(filePath));
            RevenueForecastResponse response = mapper.readValue(json, RevenueForecastResponse.class);
            validate(response);
            return response;
        } catch (IOException | RuntimeException ex) {
            throw new ForecastNotAvailableException();
        }
    }

    private static void validate(RevenueForecastResponse response) {
        if (response == null || response.months() == null || response.months().isEmpty()
                || response.annual() == null || response.annual().isEmpty()) {
            throw new IllegalArgumentException("empty forecast");
        }
        if (response.model() == null || response.model().isBlank()
                || response.mae() == null || !Double.isFinite(response.mae())
                || response.mape() != null && !Double.isFinite(response.mape())
                || response.trainingMonths() < 0 || response.holdoutMonths() < 0) {
            throw new IllegalArgumentException("invalid forecast metrics");
        }
        Instant.parse(response.generatedAt());
        for (RevenueForecastResponse.MonthPoint point : response.months()) {
            LocalDate.parse(point.date());
            if (point.lower() > point.predicted() || point.predicted() > point.upper()) {
                throw new IllegalArgumentException("inverted forecast interval");
            }
        }
        for (RevenueForecastResponse.AnnualTotal total : response.annual()) {
            if (total.months() < 1 || total.months() > 12) {
                throw new IllegalArgumentException("invalid annual month count");
            }
        }
    }
}
