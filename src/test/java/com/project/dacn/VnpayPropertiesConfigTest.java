package com.project.dacn;

import com.project.dacn.integration.payment.vnpay.VnpayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class VnpayPropertiesConfigTest {

    @Autowired
    private VnpayProperties properties;

    @Test
    void bindsVnpayConfigDefaults() {
        assertThat(properties.getPayUrl()).isNotBlank();
        assertThat(properties.getReturnUrl()).isNotBlank();
        assertThat(properties.getIpnUrl()).isNotBlank();
        assertThat(properties.getVersion()).isEqualTo("2.1.0");
    }
}
