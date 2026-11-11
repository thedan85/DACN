package com.project.dacn;

import com.project.dacn.integration.shipping.ghn.GhnProperties;
import com.project.dacn.integration.shipping.ghn.GhnWebhookController;
import com.project.dacn.integration.shipping.ghn.GhnWebhookService;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GhnWebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
class GhnWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GhnWebhookService service;

    @MockitoBean
    private GhnProperties properties;

    // JwtAuthenticationFilter is a Filter, so the slice instantiates it even with
    // addFilters = false; these mocks satisfy its constructor.
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getWebhookSecret()).thenReturn("secret");
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/ghn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void wrongTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/ghn")
                        .header("X-GHN-Webhook-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void blankConfiguredSecretReturns503() throws Exception {
        when(properties.getWebhookSecret()).thenReturn("");
        mockMvc.perform(post("/api/v1/webhooks/ghn")
                        .header("X-GHN-Webhook-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service);
    }

    @Test
    void validTokenReturns200() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/ghn")
                        .header("X-GHN-Webhook-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk());
        verify(service).handle(any());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/ghn")
                        .header("X-GHN-Webhook-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void databaseConflictReturns500() throws Exception {
        doThrow(new DataIntegrityViolationException("duplicate")).when(service).handle(any());
        mockMvc.perform(post("/api/v1/webhooks/ghn")
                        .header("X-GHN-Webhook-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isInternalServerError());
    }

    private String body() {
        return "{\"ShopID\":237833,\"OrderCode\":\"GHN-1\",\"ClientOrderCode\":\"ORD_1\","
                + "\"Type\":\"switch_status\",\"Status\":\"delivered\"}";
    }
}
