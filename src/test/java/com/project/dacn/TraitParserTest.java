package com.project.dacn;

import com.project.dacn.service.cart.TraitParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraitParserTest {

    @Test
    void parsesTemperatureRangeWithUnit() {
        TraitParser.Range range = TraitParser.parseRange("20-26 °C");

        assertThat(range).isNotNull();
        assertThat(range.min()).isEqualTo(20.0);
        assertThat(range.max()).isEqualTo(26.0);
        assertThat(range.width()).isEqualTo(6.0);
    }

    @Test
    void parsesEnDashAndSpaces() {
        TraitParser.Range range = TraitParser.parseRange("6.5 – 7.5");

        assertThat(range.min()).isEqualTo(6.5);
        assertThat(range.max()).isEqualTo(7.5);
    }

    @Test
    void parsesDecimalSizeRange() {
        TraitParser.Range range = TraitParser.parseRange("3-4 cm");

        assertThat(range.min()).isEqualTo(3.0);
        assertThat(range.max()).isEqualTo(4.0);
    }

    @Test
    void parsesSingleNumberAsPointRange() {
        TraitParser.Range range = TraitParser.parseRange("2 cm");

        assertThat(range).isNotNull();
        assertThat(range.min()).isEqualTo(2.0);
        assertThat(range.max()).isEqualTo(2.0);
        assertThat(range.width()).isZero();
    }

    @Test
    void rejectsMalformedOrInvertedRanges() {
        assertThat(TraitParser.parseRange("unknown")).isNull();
        assertThat(TraitParser.parseRange("L")).isNull();
        assertThat(TraitParser.parseRange("26-20")).isNull();
        assertThat(TraitParser.parseRange(null)).isNull();
    }

    @Test
    void parsesTankLitresFromFirstNumber() {
        assertThat(TraitParser.parseNumber("75 L")).isEqualTo(75.0);
        assertThat(TraitParser.parseNumber("40")).isEqualTo(40.0);
        assertThat(TraitParser.parseNumber("L")).isNull();
        assertThat(TraitParser.parseNumber(null)).isNull();
    }
}
