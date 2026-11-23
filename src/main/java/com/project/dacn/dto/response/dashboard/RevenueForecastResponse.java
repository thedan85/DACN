package com.project.dacn.dto.response.dashboard;

import java.util.List;

/** Monthly Prophet forecast served to the admin dashboard. */
public record RevenueForecastResponse(
        String generatedAt,
        String model,
        int trainingMonths,
        int holdoutMonths,
        Double mae,
        Double mape,
        List<MonthPoint> months,
        List<AnnualTotal> annual) {

    public record MonthPoint(
            String date,
            double predicted,
            double lower,
            double upper) {
    }

    public record AnnualTotal(
            int year,
            int months,
            double predicted,
            double lower,
            double upper) {
    }
}
