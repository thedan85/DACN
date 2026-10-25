package com.project.dacn;

import com.project.dacn.service.catalog.CatalogCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
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

    @MockitoBean
    CatalogCache cache;

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

    @Test
    void staffOrderListRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/staff/orders"))
                .andExpect(status().isUnauthorized());
    }

    static Stream<Arguments> anonymousWrites() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/products"),
                Arguments.of(HttpMethod.PUT, "/api/v1/products/PRD_1"),
                Arguments.of(HttpMethod.PATCH, "/api/v1/products/PRD_1"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/products/PRD_1"),

                Arguments.of(HttpMethod.POST, "/api/v1/variants"),
                Arguments.of(HttpMethod.PUT, "/api/v1/variants/VAR_1"),
                Arguments.of(HttpMethod.PATCH, "/api/v1/variants/VAR_1"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/variants/VAR_1"),

                Arguments.of(HttpMethod.POST, "/api/v1/categories"),
                Arguments.of(HttpMethod.PUT, "/api/v1/categories/CAT_1"),
                Arguments.of(HttpMethod.PATCH, "/api/v1/categories/CAT_1"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/categories/CAT_1"),

                Arguments.of(HttpMethod.POST, "/api/v1/attributes"),
                Arguments.of(HttpMethod.PUT, "/api/v1/attributes/ATTR_1"),
                Arguments.of(HttpMethod.PATCH, "/api/v1/attributes/ATTR_1"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/attributes/ATTR_1"),
                Arguments.of(HttpMethod.POST, "/api/v1/attribute-values"),
                Arguments.of(HttpMethod.PUT, "/api/v1/attribute-values/AV_1"),
                Arguments.of(HttpMethod.PATCH, "/api/v1/attribute-values/AV_1"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/attribute-values/AV_1"),
                Arguments.of(HttpMethod.POST, "/api/v1/product-attributes"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/product-attributes/PA_1"),
                Arguments.of(HttpMethod.POST, "/api/v1/variant-attributes"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/variant-attributes/VA_1"),

                Arguments.of(HttpMethod.POST, "/api/v1/media"),
                Arguments.of(HttpMethod.PUT, "/api/v1/media/MED_1"),
                Arguments.of(HttpMethod.PATCH, "/api/v1/media/MED_1"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/media/MED_1"),
                Arguments.of(HttpMethod.POST, "/api/v1/uploads/products/PRD_1/thumbnail"),
                Arguments.of(HttpMethod.POST, "/api/v1/uploads/variants/VAR_1"),

                Arguments.of(HttpMethod.PATCH, "/api/v1/staff/orders/ORD_1/status"),

                Arguments.of(HttpMethod.DELETE, "/api/v1/cart"),
                Arguments.of(HttpMethod.POST, "/api/v1/cart/items"),
                Arguments.of(HttpMethod.PATCH, "/api/v1/cart/items/1"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/cart/items/1"),
                Arguments.of(HttpMethod.POST, "/api/v1/cart/checkout"),
                Arguments.of(HttpMethod.POST, "/api/v1/payments/vnpay/ORD_1"),

                Arguments.of(HttpMethod.PATCH, "/api/v1/auth/me"),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/change-password"));
    }

    @ParameterizedTest(name = "{0} {1} is not public")
    @MethodSource("anonymousWrites")
    void everyNonPublicWriteRejectsAnonymousCallers(HttpMethod method, String path) throws Exception {
        mockMvc.perform(request(method, path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    static Stream<String> publicGets() {
        return Stream.of(
                "/api/v1/products",
                "/api/v1/products/search",
                "/api/v1/products/no-such-slug",
                "/api/v1/products/no-such-slug/variants",
                "/api/v1/products/PRD_1/media",
                "/api/v1/variants",
                "/api/v1/variants/no-such-id",
                "/api/v1/variants/no-such-id/media",
                "/api/v1/variants/no-such-id/attributes",
                "/api/v1/categories",
                "/api/v1/categories/no-such-id",
                "/api/v1/attributes",
                "/api/v1/attributes/no-such-id",
                "/api/v1/attributes/no-such-id/values",
                "/api/v1/attribute-values",
                "/api/v1/attribute-values/no-such-id",
                "/api/v1/product-attributes/no-such-id",
                "/api/v1/variant-attributes/no-such-id",
                "/api/v1/media",
                "/api/v1/media/no-such-id",
                "/api/v1/webhooks/vnpay/ipn",
                "/api/v1/webhooks/vnpay/return");
    }

    @ParameterizedTest(name = "{0} is public")
    @MethodSource("publicGets")
    void publicGetIsReachableAnonymously(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isNotIn(401, 403)
                        .isLessThan(500));
    }

    @Test
    void authAndMomoWebhookArePublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/webhooks/momo")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
