package com.project.dacn;

import com.project.dacn.dto.response.cart.CompatibilityResponse;
import com.project.dacn.dto.response.cart.CompatibilityResponse.Verdict;
import com.project.dacn.service.cart.CompatibilityCalculator;
import com.project.dacn.service.cart.CompatibilityCalculator.FishTraits;
import com.project.dacn.service.cart.TraitParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static com.project.dacn.dto.response.cart.CompatibilityResponse.Status.FAIL;
import static com.project.dacn.dto.response.cart.CompatibilityResponse.Status.UNKNOWN;
import static com.project.dacn.dto.response.cart.CompatibilityResponse.Status.WARN;
import static com.project.dacn.dto.response.cart.CompatibilityResponse.Verdict.CAUTION;
import static com.project.dacn.dto.response.cart.CompatibilityResponse.Verdict.INFO;
import static com.project.dacn.dto.response.cart.CompatibilityResponse.Verdict.NOT_RECOMMENDED;
import static com.project.dacn.dto.response.cart.CompatibilityResponse.Verdict.SAFE;
import static org.assertj.core.api.Assertions.assertThat;

class CompatibilityCalculatorTest {

    private static FishTraits fish(String name, String temp, String ph,
            String temperament, String diet, String size, Double tank) {
        return new FishTraits(name, name,
                TraitParser.parseRange(temp), TraitParser.parseRange(ph),
                TraitParser.parseRange(size), tank, temperament, diet);
    }

    private static FishTraits peaceful(String name, String temp, String ph) {
        return fish(name, temp, ph, "Peaceful", "Omnivore", "4-5 cm", 75.0);
    }

    private static boolean hasCheck(CompatibilityResponse result, String code, CompatibilityResponse.Status status) {
        return result.checks().stream()
                .anyMatch(check -> check.code().equals(code) && check.status() == status);
    }

    @Test
    void singleSpeciesReturnsInfo() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(
                List.of(peaceful("Ember Tetra", "20-26 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(INFO);
        assertThat(result.summary())
                .isEqualTo("Add another fish species to check compatibility.");
        assertThat(result.checks()).isEmpty();
        assertThat(result.recommendedTankLitres()).isNull();
    }

    @Test
    void compatiblePairReturnsSafeAndHidesPassingChecks() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                peaceful("Ember Tetra", "20-26 °C", "6.0-7.5"),
                fish("Neon Tetra", "22-28 °C", "6.5-7.5",
                        "Peaceful", "Omnivore", "4-5 cm", 200.0)));

        assertThat(result.verdict()).isEqualTo(SAFE);
        assertThat(result.summary())
                .isEqualTo("Selected species share compatible water parameters and behavior.");
        assertThat(result.checks()).isEmpty();
        assertThat(result.recommendedTankLitres()).isEqualTo(200);
    }

    @Test
    void disjointTemperatureReturnsNotRecommended() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                peaceful("Cool Fish", "20-24 °C", "6.0-7.5"),
                peaceful("Warm Fish", "26-30 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(NOT_RECOMMENDED);
        assertThat(result.summary())
                .isEqualTo("These species are not compatible in one aquarium.");
        assertThat(hasCheck(result, "TEMPERATURE", FAIL)).isTrue();
    }

    @Test
    void narrowSharedTemperatureReturnsCaution() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                peaceful("Cool Fish", "20-24 °C", "6.0-7.5"),
                peaceful("Warm Fish", "23-28 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(CAUTION);
        assertThat(result.summary())
                .isEqualTo("Some species need monitoring before cohabitation.");
        assertThat(hasCheck(result, "TEMPERATURE", WARN)).isTrue();
    }

    @Test
    void globalIntersectionCatchesThreeWayNarrowRange() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                peaceful("A", "20-24 °C", "6.0-7.5"),
                peaceful("B", "22-26 °C", "6.0-7.5"),
                peaceful("C", "24-28 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(CAUTION);
        assertThat(result.checks())
                .anyMatch(check -> check.code().equals("TEMPERATURE")
                        && check.message().contains("24"));
    }

    @Test
    void disjointPhReturnsNotRecommended() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                peaceful("Acid Fish", "20-26 °C", "6.0-6.5"),
                peaceful("Alkaline Fish", "20-26 °C", "7.5-8.0")));

        assertThat(result.verdict()).isEqualTo(NOT_RECOMMENDED);
        assertThat(result.summary())
                .isEqualTo("These species are not compatible in one aquarium.");
        assertThat(hasCheck(result, "PH", FAIL)).isTrue();
    }

    @Test
    void narrowSharedPhReturnsCaution() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                peaceful("Acid Fish", "20-26 °C", "6.0-6.4"),
                peaceful("Alkaline Fish", "20-26 °C", "6.3-7.0")));

        assertThat(result.verdict()).isEqualTo(CAUTION);
        assertThat(hasCheck(result, "PH", WARN)).isTrue();
    }

    @Test
    void exactTemperatureWidthPasses() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                peaceful("Cool Fish", "20-24 °C", "6.0-7.5"),
                peaceful("Warm Fish", "22-26 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(SAFE);
    }

    @Test
    void exactPhWidthPasses() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                peaceful("Acid Fish", "20-26 °C", "6.0-6.4"),
                peaceful("Alkaline Fish", "20-26 °C", "6.1-7.0")));

        assertThat(result.verdict()).isEqualTo(SAFE);
    }

    @Test
    void exactPredationThresholdWarns() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                fish("Predator", "20-26 °C", "6.0-7.5",
                        "Peaceful", "Carnivore", "6 cm", 200.0),
                fish("Prey", "20-26 °C", "6.0-7.5",
                        "Peaceful", "Omnivore", "4 cm", 75.0)));

        assertThat(result.verdict()).isEqualTo(CAUTION);
        assertThat(hasCheck(result, "PREDATION", WARN)).isTrue();
    }

    @Test
    void semiAggressiveSpeciesReturnsCaution() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                fish("Tiger Barb", "24-28 °C", "6.0-7.5", "Semi-aggressive", "Omnivore", "5-6 cm", 75.0),
                peaceful("Cherry Barb", "20-26 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(CAUTION);
        assertThat(hasCheck(result, "TEMPERAMENT", WARN)).isTrue();
        assertThat(result.checks())
                .anyMatch(check -> check.message().contains("Tiger Barb"));
    }

    @Test
    void largeCarnivoreTriggersPredationFailure() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                fish("Predator", "20-28 °C", "6.0-7.5", "Peaceful", "Carnivore", "8-10 cm", 200.0),
                peaceful("Prey", "20-26 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(NOT_RECOMMENDED);
        assertThat(hasCheck(result, "PREDATION", FAIL)).isTrue();
    }

    @Test
    void marginalPredationRatioReturnsCaution() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                fish("Predator", "20-28 °C", "6.0-7.5", "Peaceful", "Carnivore", "6-8 cm", 200.0),
                peaceful("Prey", "20-26 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(CAUTION);
        assertThat(hasCheck(result, "PREDATION", WARN)).isTrue();
    }

    @Test
    void missingTemperatureReturnsUnknown() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                fish("Unknown Fish", "unknown", "6.0-7.5", "Peaceful", "Omnivore", "4-5 cm", 75.0),
                peaceful("Known Fish", "20-26 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(result.summary())
                .isEqualTo("Not enough trait data to confirm compatibility.");
        assertThat(hasCheck(result, "TEMPERATURE", UNKNOWN)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL, 6.0-7.5, 4-5 cm, Omnivore, TEMPERATURE",
            "20-26 °C, NULL, 4-5 cm, Omnivore, PH",
            "20-26 °C, neutral, 4-5 cm, Omnivore, PH",
            "20-26 °C, 6.0-7.5, NULL, Omnivore, PREDATION",
            "20-26 °C, 6.0-7.5, L, Omnivore, PREDATION",
            "20-26 °C, 6.0-7.5, 4-5 cm, NULL, PREDATION",
            "20-26 °C, 6.0-7.5, 4-5 cm, Weird, PREDATION"
    })
    void missingOrMalformedTraitsReturnUnknown(String temp, String ph,
            String size, String diet, String expectedCode) {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                fish("Partial Fish", temp, ph, "Peaceful", diet, size, 75.0),
                peaceful("Known Fish", "20-26 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(hasCheck(result, expectedCode, UNKNOWN)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {"NULL", "Grumpy"})
    void missingOrMalformedTemperamentReturnsUnknown(String temperament) {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                fish("Unknown Temperament", "20-26 °C", "6.0-7.5",
                        temperament, "Omnivore", "4-5 cm", 75.0),
                peaceful("Known Fish", "20-26 °C", "6.0-7.5")));

        assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(hasCheck(result, "TEMPERAMENT", UNKNOWN)).isTrue();
    }

    @Test
    void missingTankValueOmitsRecommendation() {
        CompatibilityResponse result = CompatibilityCalculator.evaluate(List.of(
                fish("A", "20-26 °C", "6.0-7.5", "Peaceful", "Omnivore", "4-5 cm", 75.0),
                fish("B", "20-26 °C", "6.0-7.5", "Peaceful", "Omnivore", "4-5 cm", null)));

        assertThat(result.recommendedTankLitres()).isNull();
    }
}
