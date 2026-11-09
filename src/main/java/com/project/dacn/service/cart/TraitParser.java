package com.project.dacn.service.cart;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TraitParser {

    private static final Pattern RANGE = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)\\s*[-–]\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private TraitParser() {
    }

    public static Range parseRange(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher range = RANGE.matcher(raw);
        if (range.find()) {
            double min = Double.parseDouble(range.group(1));
            double max = Double.parseDouble(range.group(2));
            return min > max ? null : new Range(min, max);
        }
        Matcher number = NUMBER.matcher(raw);
        if (number.find()) {
            double value = Double.parseDouble(number.group());
            return new Range(value, value);
        }
        return null;
    }

    public static Double parseNumber(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher number = NUMBER.matcher(raw);
        return number.find() ? Double.parseDouble(number.group()) : null;
    }

    public record Range(double min, double max) {
        public double width() {
            return max - min;
        }
    }
}
