package com.project.dacn;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the URL-level authorization rules, i.e. the rules that protect the app
 * when it is exposed through a tunnel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityRulesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void cartRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void ordersRequireToken() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void paymentsRequireToken() throws Exception {
        mockMvc.perform(get("/api/v1/payments/orders/ORD_1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void catalogReadIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk());
    }

    @Test
    void catalogWriteRequiresToken() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void vnpayIpnIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/vnpay/ipn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("99"));
    }
}
