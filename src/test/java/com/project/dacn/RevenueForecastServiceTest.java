package com.project.dacn;

import com.project.dacn.common.exception.ForecastNotAvailableException;
import com.project.dacn.service.dashboard.RevenueForecastService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RevenueForecastServiceTest {

    private static final String MESSAGE = "Revenue forecast is not available.";
    private static final String VALID_JSON = """
            {
              "generatedAt": "2026-11-23T10:00:00+07:00",
              "model": "Prophet",
              "trainingMonths": 36,
              "holdoutMonths": 6,
              "mae": 167965.57,
              "mape": null,
              "months": [
                {"date": "2026-11-01", "predicted": 10356310.87,
                 "lower": 10189870.20, "upper": 10510301.47},
                {"date": "2026-12-01", "predicted": 10277874.56,
                 "lower": 10120610.60, "upper": 10431973.52}
              ],
              "annual": [
                {"year": 2026, "months": 2, "predicted": 20634185.43,
                 "lower": 20310480.80, "upper": 20942274.99}
              ]
            }
            """;

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsValidForecastFile() throws Exception {
        Path file = tempDir.resolve("forecast.json");
        Files.writeString(file, VALID_JSON);

        var response = new RevenueForecastService(file.toString(), mapper).getForecast();

        assertThat(response.generatedAt()).isEqualTo("2026-11-23T10:00:00+07:00");
        assertThat(response.model()).isEqualTo("Prophet");
        assertThat(response.trainingMonths()).isEqualTo(36);
        assertThat(response.mape()).isNull();
        assertThat(response.months()).hasSize(2);
        assertThat(response.months().get(0).date()).isEqualTo("2026-11-01");
        assertThat(response.months().get(0).predicted()).isEqualTo(10356310.87);
        assertThat(response.annual()).hasSize(1);
        assertThat(response.annual().get(0).year()).isEqualTo(2026);
        assertThat(response.annual().get(0).months()).isEqualTo(2);
    }

    @Test
    void rejectsMissingFile() {
        RevenueForecastService service =
                new RevenueForecastService(tempDir.resolve("missing.json").toString(), mapper);

        assertThatThrownBy(service::getForecast)
                .isInstanceOf(ForecastNotAvailableException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        Path file = tempDir.resolve("forecast.json");
        Files.writeString(file, "{");

        RevenueForecastService service = new RevenueForecastService(file.toString(), mapper);

        assertThatThrownBy(service::getForecast)
                .isInstanceOf(ForecastNotAvailableException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void rejectsInvalidContractValues() throws Exception {
        String[] invalidPayloads = {
                VALID_JSON.replace("\"model\": \"Prophet\"", "\"model\": \"\""),
                VALID_JSON.replace("\"mae\": 167965.57", "\"mae\": null"),
                VALID_JSON.replace("\"trainingMonths\": 36", "\"trainingMonths\": -1"),
                VALID_JSON.replace("\"months\": 2,", "\"months\": 13,")
        };

        for (String payload : invalidPayloads) {
            Path file = tempDir.resolve("forecast.json");
            Files.writeString(file, payload);
            RevenueForecastService service =
                    new RevenueForecastService(file.toString(), mapper);

            assertThatThrownBy(service::getForecast)
                    .isInstanceOf(ForecastNotAvailableException.class)
                    .hasMessage(MESSAGE);
        }
    }

    @Test
    void rejectsInvertedInterval() throws Exception {
        Path file = tempDir.resolve("forecast.json");
        Files.writeString(file, VALID_JSON.replace("\"lower\": 10189870.20", "\"lower\": 99999999.00"));

        RevenueForecastService service = new RevenueForecastService(file.toString(), mapper);

        assertThatThrownBy(service::getForecast)
                .isInstanceOf(ForecastNotAvailableException.class)
                .hasMessage(MESSAGE);
    }
}
