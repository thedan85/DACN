package com.project.dacn.service.cart;

import com.project.dacn.dto.response.cart.CompatibilityResponse;
import com.project.dacn.dto.response.cart.CompatibilityResponse.Check;
import com.project.dacn.dto.response.cart.CompatibilityResponse.Status;
import com.project.dacn.dto.response.cart.CompatibilityResponse.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CompatibilityCalculator {

    private static final String SAFE_SUMMARY =
            "Selected species share compatible water parameters and behavior.";
    private static final String CAUTION_SUMMARY =
            "Some species need monitoring before cohabitation.";
    private static final String NOT_RECOMMENDED_SUMMARY =
            "These species are not compatible in one aquarium.";
    private static final String UNKNOWN_SUMMARY =
            "Not enough trait data to confirm compatibility.";
    private static final String INFO_SUMMARY =
            "Add another fish species to check compatibility.";

    private static final Set<String> TEMPERAMENTS = Set.of("Peaceful", "Semi-aggressive");
    private static final Set<String> DIETS = Set.of("Carnivore", "Omnivore", "Herbivore");

    private CompatibilityCalculator() {
    }

    public record FishTraits(
            String productId, String name,
            TraitParser.Range temperature, TraitParser.Range ph,
            TraitParser.Range size, Double minTankLitres,
            String temperament, String diet) {
    }

    public static CompatibilityResponse evaluate(List<FishTraits> fish) {
        if (fish == null || fish.size() < 2) {
            return new CompatibilityResponse(Verdict.INFO, INFO_SUMMARY, List.of(), null);
        }

        List<Check> checks = new ArrayList<>();
        evaluateWater(checks, "TEMPERATURE", "temperature",
                fish.stream().map(FishTraits::temperature).toList(), 2.0);
        evaluateWater(checks, "PH", "pH",
                fish.stream().map(FishTraits::ph).toList(), 0.3);
        evaluateTemperament(checks, fish);
        evaluatePredation(checks, fish);

        Verdict verdict = verdict(checks);
        Integer tank = recommendedTank(fish);
        return new CompatibilityResponse(verdict, summary(verdict), List.copyOf(checks), tank);
    }

    private static void evaluateWater(List<Check> checks, String code, String label,
            List<TraitParser.Range> ranges, double minimumWidth) {
        if (ranges.stream().anyMatch(range -> range == null)) {
            checks.add(new Check(code, Status.UNKNOWN,
                    capitalized(label) + " data is missing for one or more species."));
            return;
        }

        double min = ranges.stream().mapToDouble(TraitParser.Range::min).max().orElseThrow();
        double max = ranges.stream().mapToDouble(TraitParser.Range::max).min().orElseThrow();
        if (min > max) {
            checks.add(new Check(code, Status.FAIL,
                    "Selected species have no shared " + label + " range."));
        } else if (max - min < minimumWidth) {
            checks.add(new Check(code, Status.WARN,
                    "Shared " + label + " range is only " + number(min) + "-" + number(max)
                            + (code.equals("TEMPERATURE") ? " °C." : ".")));
        }
    }

    private static void evaluateTemperament(List<Check> checks, List<FishTraits> fish) {
        for (FishTraits species : fish) {
            if (species.temperament() == null || !TEMPERAMENTS.contains(species.temperament())) {
                checks.add(new Check("TEMPERAMENT", Status.UNKNOWN,
                        "Temperament data is missing or unknown for " + species.name() + "."));
            }
        }

        List<String> semiAggressive = fish.stream()
                .filter(species -> "Semi-aggressive".equals(species.temperament()))
                .map(FishTraits::name)
                .toList();
        if (!semiAggressive.isEmpty()) {
            checks.add(new Check("TEMPERAMENT", Status.WARN,
                    "Semi-aggressive species present: " + String.join(", ", semiAggressive)
                            + ". Monitor nipping and territory."));
        }
    }

    private static void evaluatePredation(List<Check> checks, List<FishTraits> fish) {
        for (FishTraits species : fish) {
            if (species.size() == null || species.diet() == null
                    || !DIETS.contains(species.diet())) {
                checks.add(new Check("PREDATION", Status.UNKNOWN,
                        "Size or diet data is missing or unknown for " + species.name() + "."));
            }
        }

        for (FishTraits predator : fish) {
            if (!"Carnivore".equals(predator.diet()) || predator.size() == null) {
                continue;
            }
            for (FishTraits other : fish) {
                if (other == predator || other.size() == null) {
                    continue;
                }
                double ratio = predator.size().max() / other.size().max();
                if (ratio >= 2.0) {
                    checks.add(new Check("PREDATION", Status.FAIL,
                            predator.name() + " is large enough to prey on " + other.name() + "."));
                } else if (ratio >= 1.5) {
                    checks.add(new Check("PREDATION", Status.WARN,
                            predator.name() + " may harass or prey on " + other.name() + "."));
                }
            }
        }
    }

    private static Verdict verdict(List<Check> checks) {
        if (checks.stream().anyMatch(check -> check.status() == Status.FAIL)) {
            return Verdict.NOT_RECOMMENDED;
        }
        if (checks.stream().anyMatch(check -> check.status() == Status.WARN)) {
            return Verdict.CAUTION;
        }
        if (checks.stream().anyMatch(check -> check.status() == Status.UNKNOWN)) {
            return Verdict.UNKNOWN;
        }
        return Verdict.SAFE;
    }

    private static String summary(Verdict verdict) {
        return switch (verdict) {
            case SAFE -> SAFE_SUMMARY;
            case CAUTION -> CAUTION_SUMMARY;
            case NOT_RECOMMENDED -> NOT_RECOMMENDED_SUMMARY;
            case UNKNOWN -> UNKNOWN_SUMMARY;
            case INFO -> INFO_SUMMARY;
        };
    }

    private static Integer recommendedTank(List<FishTraits> fish) {
        if (fish.stream().anyMatch(species -> species.minTankLitres() == null)) {
            return null;
        }
        return fish.stream().mapToInt(species -> species.minTankLitres().intValue()).max().orElseThrow();
    }

    private static String capitalized(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
