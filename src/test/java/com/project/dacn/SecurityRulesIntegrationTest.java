package com.project.dacn;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.common.exception.RateLimitUnavailableException;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.integration.realtime.RealtimeAdmissionService;
import com.project.dacn.integration.realtime.RealtimeCredentialResponse;
import com.project.dacn.integration.realtime.RealtimePrincipal;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.JwtService;
import com.project.dacn.security.RateLimiter;
import com.project.dacn.service.catalog.CatalogCache;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the URL-level authorization rules, i.e. the rules that protect the app
 * when it is exposed through a tunnel.
 */
@SpringBootTest(properties = "app.realtime.trusted-client-ip-header=CF-Connecting-IP")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityRulesIntegrationTest {

    @MockitoBean
    CatalogCache cache;

    @MockitoBean
    RateLimiter rateLimiter;

    @MockitoSpyBean
    RealtimeAdmissionService admissionService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Value("${jwt.secret}")
    private String testJwtSecret;

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
        mockMvc.perform(get("/api/v1/orders/ORD_1/reviews"))
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
    void realtimeCredentialsArePublicButReturnUnavailableWhenDisabled() throws Exception {
        mockMvc.perform(post("/api/v1/realtime/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void publicCredentialSuccessUsesTheTrustedIpAndReturnsOnlyTheCredentialContract() throws Exception {
        doReturn(new RealtimeCredentialResponse("test-access-token", Instant.parse("2026-11-04T10:05:00Z"),
                List.of(), null)).when(admissionService).issue(RealtimePrincipal.guest(), List.of());

        mockMvc.perform(post("/api/v1/realtime/credentials")
                        .header("CF-Connecting-IP", "203.0.113.7")
                        .header("X-Forwarded-For", "198.51.100.9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.accessToken").value("test-access-token"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-04T10:05:00Z"))
                .andExpect(jsonPath("$.data.admittedProductIds").isEmpty())
                .andExpect(jsonPath("$.data.inboxTopic").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(4)));

        verify(rateLimiter).checkStrict("realtime:cred:ip:203.0.113.7", 60, Duration.ofMinutes(1));
        verifyNoMoreInteractions(rateLimiter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer not-a-jwt", "Bearer ", "Basic abc"})
    void realtimeCredentialsRejectInvalidSuppliedBearerThroughJwtFilter(String header) throws Exception {
        mockMvc.perform(post("/api/v1/realtime/credentials")
                        .header(HttpHeaders.AUTHORIZATION, header)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void malformedSignedBearerIsRejectedEvenWhenJwtFilterSwallowsClaimFailure() throws Exception {
        mockMvc.perform(post("/api/v1/realtime/credentials")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + signedToken(List.of("access"), Instant.now().plusSeconds(60)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void expiredAccessAndRefreshBearersCannotObtainGuestCredentials() throws Exception {
        for (String token : List.of(signedToken("access", Instant.now().minusSeconds(60)),
                jwtService.generateRefreshToken("USR_missing", "test-refresh"))) {
            mockMvc.perform(post("/api/v1/realtime/credentials")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }

        verifyNoInteractions(rateLimiter);
    }

    @Test
    @WithMockUser
    void anExistingSecurityContextCannotHideInvalidSuppliedBearer() throws Exception {
        mockMvc.perform(post("/api/v1/realtime/credentials")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(rateLimiter);
    }

    @Test
    @Transactional
    void activeBearerUsesInternalAccountLimitThroughTheRealJwtFilter() throws Exception {
        User user = realtimeUser(UserStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/realtime/credentials")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtService.generateAccessToken(user.getUserId(), user.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));

        verify(rateLimiter).checkStrict("realtime:cred:ip:203.0.113.9", 60, Duration.ofMinutes(1));
        verify(rateLimiter).checkStrict("realtime:cred:user:" + user.getId(), 6, Duration.ofMinutes(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional
    void requiredLimitFailureReturns503BeforeAdmissionThroughRealSecurityChain(boolean accountCheck)
            throws Exception {
        String key = "realtime:cred:ip:203.0.113.9";
        var request = post("/api/v1/realtime/credentials")
                .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}")
                .with(req -> {
                    req.setRemoteAddr("203.0.113.9");
                    return req;
                });
        if (accountCheck) {
            User user = realtimeUser(UserStatus.ACTIVE);
            key = "realtime:cred:user:" + user.getId();
            request.header(HttpHeaders.AUTHORIZATION,
                    "Bearer " + jwtService.generateAccessToken(user.getUserId(), user.getEmail()));
        }
        doThrow(new RateLimitUnavailableException())
                .when(rateLimiter).checkStrict(eq(key), anyInt(), any());
        doReturn(new RealtimeCredentialResponse("must-not-issue", Instant.parse("2026-11-04T10:05:00Z"),
                List.of(), null)).when(admissionService).issue(any(), anyList());

        var response = mockMvc.perform(request)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("realtime credentials unavailable"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).hasSizeLessThan(200).doesNotContain("must-not-issue");
        verify(admissionService, never()).issue(any(), anyList());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"LOCKED", "DELETED"})
    @Transactional
    void inactiveBearersCannotDowngradeThroughTheRealJwtFilter(UserStatus userStatus) throws Exception {
        User user = realtimeUser(userStatus);

        mockMvc.perform(post("/api/v1/realtime/credentials")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtService.generateAccessToken(user.getUserId(), user.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void realFilterChainAllowsBodyAt32768BytesToReachDisabledAdmission() throws Exception {
        mockMvc.perform(post("/api/v1/realtime/credentials")
                        .contentType(MediaType.APPLICATION_JSON).content(paddedRealtimeBody(32768)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void realFilterChainRejectsBodyAt32769BytesBeforeRateLimits() throws Exception {
        var result = mockMvc.perform(post("/api/v1/realtime/credentials")
                        .contentType(MediaType.APPLICATION_JSON).content(paddedRealtimeBody(32769)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.message").value("request body too large"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).hasSizeLessThan(200);
        verifyNoInteractions(rateLimiter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "PUT", "PATCH", "DELETE"})
    void otherRealtimeCredentialMethodsRequireAuthentication(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), "/api/v1/realtime/credentials")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void traceIsRejectedByTheRealSecurityFirewall() throws Exception {
        mockMvc.perform(request(HttpMethod.TRACE, "/api/v1/realtime/credentials"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rateLimiter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/realtime/credentials/", "/api/v1/realtime/credentials/other",
            "/api/v1/realtime/other"})
    void otherRealtimePostPathsRequireAuthentication(String path) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(rateLimiter);
    }

    private String signedToken(Object type, Instant expiresAt) {
        return Jwts.builder().subject("USR_missing").claim("type", type)
                .expiration(Date.from(expiresAt))
                .signWith(Keys.hmacShaKeyFor(testJwtSecret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private User realtimeUser(UserStatus status) {
        Role role = roleRepository.save(Role.builder().code("REALTIME_TEST").name("Realtime test").build());
        return userRepository.saveAndFlush(User.builder()
                .email("realtime-" + UUID.randomUUID() + "@example.invalid")
                .status(status).role(role).build());
    }

    private static String paddedRealtimeBody(int bytes) {
        String body = "{\"productIds\":[]}";
        return body + " ".repeat(bytes - body.length());
    }

    @Test
    void vnpayIpnIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/vnpay/ipn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("99"));
    }

    @Test
    @WithMockUser
    void reviewCreateDoesNotRequireCatalogWritePermission() throws Exception {
        mockMvc.perform(post("/api/v1/variants/VAR_1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staffOrderListRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/staff/orders"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/staff/orders/ORD_1/reviews"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dashboardRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @WithMockUser
    void dashboardRequiresOrderReadAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/summary"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
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
                Arguments.of(HttpMethod.POST, "/api/v1/variants/VAR_1/reviews"),

                Arguments.of(HttpMethod.PATCH, "/api/v1/reviews/REV_1"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/reviews/REV_1"),

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
                Arguments.of(HttpMethod.POST, "/api/v1/admin/orders/ORD_1/complete"),

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
                "/api/v1/variants/no-such-id/reviews",
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
