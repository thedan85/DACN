package com.project.dacn;

import com.project.dacn.integration.shipping.ghn.GhnConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class GhnConfigTest {

    @Test
    void timeoutsAreConfigured() {
        SimpleClientHttpRequestFactory factory = new GhnConfig().ghnRequestFactory();
        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(3000);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(10000);
    }
}
