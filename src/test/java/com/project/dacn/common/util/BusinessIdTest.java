package com.project.dacn.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessIdTest {

    @Test
    void slugStripsDiacriticsAndSymbols() {
        assertThat(BusinessId.slug("Cá Cảnh")).isEqualTo("ca-canh");
        assertThat(BusinessId.slug("  Red  Cherry__Tetra ")).isEqualTo("red-cherry-tetra");
        assertThat(BusinessId.slug("Đèn LED 30cm")).isEqualTo("den-led-30cm");
        assertThat(BusinessId.slug("!!!")).isEqualTo("unnamed");
        assertThat(BusinessId.slug(null)).isEqualTo("unnamed");
    }

    @Test
    void ofJoinsSluggedParts() {
        assertThat(BusinessId.of("CAT", "Betta")).isEqualTo("CAT_betta");
        assertThat(BusinessId.of("AV", "Màu sắc", "Đỏ")).isEqualTo("AV_mau-sac_do");
    }

    @Test
    void randomHasPrefixAndEightHexChars() {
        assertThat(BusinessId.random("ORD")).matches("ORD_[0-9a-f]{8}");
        assertThat(BusinessId.random("ORD")).isNotEqualTo(BusinessId.random("ORD"));
    }

    @Test
    void randomTokenHasEightHexCharsAndNoPrefix() {
        assertThat(BusinessId.randomToken()).matches("[0-9a-f]{8}");
        assertThat(BusinessId.randomToken()).isNotEqualTo(BusinessId.randomToken());
    }
}
