package com.project.dacn;

import com.project.dacn.integration.shipping.ghn.GhnStatusMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class GhnStatusMapperTest {

    @ParameterizedTest
    @CsvSource({
        "delivered, DELIVERED",
        "cancel, CANCELLED",
        "returned, RETURNED",
        "picked, SHIPPING",
        "storing, SHIPPING",
        "sorting, SHIPPING",
        "transporting, SHIPPING",
        "delivering, SHIPPING",
        "money_collect_delivering, SHIPPING",
        "ready_to_pick, NONE",
        "picking, NONE",
        "money_collect_picking, NONE",
        "delivery_fail, NONE",
        "waiting_to_return, NONE",
        "return, NONE",
        "return_transporting, NONE",
        "return_sorting, NONE",
        "returning, NONE",
        "return_fail, NONE",
        "exception, NONE",
        "lost, NONE",
        "damage, NONE",
        "scrap, NONE",
        "unknown, NONE"
    })
    void mapsStatus(String status, GhnStatusMapper.Event event) {
        assertThat(GhnStatusMapper.map(status)).isEqualTo(event);
    }
}
