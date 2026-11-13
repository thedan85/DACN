package com.project.dacn;

import com.project.dacn.integration.payment.vietqr.VietQrProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class VietQrPropertiesConfigTest {

    @Autowired
    private VietQrProperties properties;

    @Test
    void bindsVietQrConfigDefaults() {
        assertThat(properties.getTemplate()).isEqualTo("compact2");
        assertThat(properties.getImageBaseUrl()).isEqualTo("https://img.vietqr.io/image");
    }
}
