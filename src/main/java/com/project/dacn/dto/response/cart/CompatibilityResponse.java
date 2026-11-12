package com.project.dacn.dto.response.cart;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(example = """
        {
          "verdict": "CAUTION",
          "summary": "Some species need monitoring before cohabitation.",
          "checks": [
            {
              "code": "TEMPERATURE",
              "status": "WARN",
              "message": "Shared temperature range is only 24-26 °C."
            }
          ],
          "recommendedTankLitres": 200
        }
        """)
public record CompatibilityResponse(
        Verdict verdict,
        String summary,
        List<Check> checks,
        Integer recommendedTankLitres) {

    public enum Verdict {
        SAFE, CAUTION, NOT_RECOMMENDED, UNKNOWN, INFO
    }

    public enum Status {
        PASS, WARN, FAIL, UNKNOWN
    }

    public record Check(String code, Status status, String message) {
    }
}
