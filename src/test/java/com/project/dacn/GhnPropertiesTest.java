package com.project.dacn;

import com.project.dacn.integration.shipping.ghn.GhnProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GhnPropertiesTest {

    @Test
    void toStringHidesSecrets() {
        GhnProperties properties = new GhnProperties();
        properties.setToken("token-secret");
        properties.setWebhookSecret("webhook-secret");
        assertThat(properties.toString()).doesNotContain("token-secret", "webhook-secret");
    }
}
