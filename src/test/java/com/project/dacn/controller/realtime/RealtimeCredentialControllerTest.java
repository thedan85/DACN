package com.project.dacn.controller.realtime;

import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.exception.RateLimitExceededException;
import com.project.dacn.entity.auth.User;
import com.project.dacn.integration.realtime.RealtimeAdmissionService;
import com.project.dacn.integration.realtime.RealtimeCredentialAuthentication;
import com.project.dacn.integration.realtime.RealtimeCredentialResponse;
import com.project.dacn.integration.realtime.RealtimePrincipal;
import com.project.dacn.integration.realtime.RealtimeProperties;
import com.project.dacn.integration.realtime.RealtimeRequestValidationException;
import com.project.dacn.integration.realtime.RealtimeUnavailableException;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.JwtService;
import com.project.dacn.security.RateLimiter;
import com.project.dacn.security.TrustedClientIpResolver;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RealtimeCredentialControllerTest {

    private static final String PATH = "/api/v1/realtime/credentials";
    private static final String BODY = "{\"productIds\":[\"p1\"]}";
    private static final RealtimeCredentialResponse CREDENTIALS = new RealtimeCredentialResponse(
            "access-token", Instant.parse("2026-11-04T10:05:00Z"), List.of("p1"), null);

    private final JwtService jwtService = mock(JwtService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final RealtimeAdmissionService admissionService = mock(RealtimeAdmissionService.class);
    private RealtimeProperties properties;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new RealtimeProperties();
        mvc = mvcUsing(rateLimiter);
    }

    private MockMvc mvcUsing(RateLimiter limiter) {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        ReflectionTestUtils.setField(advice, "messageSource", messages);
        var controller = new RealtimeCredentialController(
                new RealtimeCredentialAuthentication(jwtService, userRepository),
                new TrustedClientIpResolver(properties),
                limiter,
                admissionService);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .build();
    }

    @Test
    void noBearerIsGuestAndResponseIsNotCached() throws Exception {
        when(admissionService.issue(any(), anyList())).thenReturn(CREDENTIALS);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-04T10:05:00Z"))
                .andExpect(jsonPath("$.data.admittedProductIds[0]").value("p1"))
                .andExpect(jsonPath("$.data.inboxTopic").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(4)));

        verify(admissionService).issue(RealtimePrincipal.guest(), List.of("p1"));
    }

    @Test
    void validActiveBearerIsAuthenticated() throws Exception {
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(jwtService.validateAccessToken("good-token")).thenReturn(true);
        when(jwtService.extractUserId("good-token")).thenReturn("USR_7");
        when(userRepository.findByUserId("USR_7")).thenReturn(Optional.of(user));
        when(admissionService.issue(any(), anyList())).thenReturn(CREDENTIALS);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
                .andExpect(status().isOk());

        verify(admissionService).issue(RealtimePrincipal.authenticated(7L), List.of("p1"));
        verify(rateLimiter).checkStrict("realtime:cred:user:7", 6, Duration.ofMinutes(1));
    }

    @Test
    void lockedAndDeletedAccountsAreRejected() throws Exception {
        for (UserStatus status : List.of(UserStatus.LOCKED, UserStatus.DELETED)) {
            User user = mock(User.class);
            when(user.getStatus()).thenReturn(status);
            when(jwtService.validateAccessToken("token")).thenReturn(true);
            when(jwtService.extractUserId("token")).thenReturn("USR_1");
            when(userRepository.findByUserId("USR_1")).thenReturn(Optional.of(user));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }

        verify(admissionService, never()).issue(any(), anyList());
    }

    @Test
    void jwtClaimValidationFailuresReturn401WithoutGuestAdmission() throws Exception {
        when(jwtService.validateAccessToken("invalid-claims"))
                .thenThrow(new JwtException("invalid claims"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-claims"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(admissionService, never()).issue(any(), anyList());
    }

    @Test
    void tokenExpiringBetweenValidationAndSubjectExtractionReturns401() throws Exception {
        when(jwtService.validateAccessToken("expiring")).thenReturn(true);
        when(jwtService.extractUserId("expiring")).thenThrow(new JwtException("expired"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expiring"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(admissionService, never()).issue(any(), anyList());
    }

    @Test
    void malformedExpiredOrUnknownBearerIsNeverDowngradedToGuest() throws Exception {
        when(jwtService.validateAccessToken(anyString())).thenReturn(false);
        when(jwtService.validateAccessToken("expired")).thenReturn(false);

        for (String header : List.of("Bearer expired", "Basic abc", "Bearer unknown")) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                            .header(HttpHeaders.AUTHORIZATION, header))
                    .andExpect(status().isUnauthorized());
        }

        when(jwtService.validateAccessToken("missing-user")).thenReturn(true);
        when(jwtService.extractUserId("missing-user")).thenReturn("USR_X");
        when(userRepository.findByUserId("USR_X")).thenReturn(Optional.empty());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer missing-user"))
                .andExpect(status().isUnauthorized());

        verify(admissionService, never()).issue(any(), anyList());
    }

    @Test
    void rateLimitsUsePerIpAndPerAccountKeys() throws Exception {
        User user = mock(User.class);
        when(user.getId()).thenReturn(9L);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(jwtService.validateAccessToken("token")).thenReturn(true);
        when(jwtService.extractUserId("token")).thenReturn("USR_9");
        when(userRepository.findByUserId("USR_9")).thenReturn(Optional.of(user));
        when(admissionService.issue(any(), anyList())).thenReturn(CREDENTIALS);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isOk());

        verify(rateLimiter).checkStrict("realtime:cred:ip:203.0.113.9", 60, Duration.ofMinutes(1));
        verify(rateLimiter).checkStrict("realtime:cred:user:9", 6, Duration.ofMinutes(1));
        var order = inOrder(rateLimiter, admissionService);
        order.verify(rateLimiter).checkStrict("realtime:cred:ip:203.0.113.9", 60, Duration.ofMinutes(1));
        order.verify(rateLimiter).checkStrict("realtime:cred:user:9", 6, Duration.ofMinutes(1));
        order.verify(admissionService).issue(RealtimePrincipal.authenticated(9L), List.of("p1"));
    }

    @Test
    void spoofedForwardedForDoesNotChangeTheKeyWhenNoTrustedHeaderIsConfigured() throws Exception {
        when(admissionService.issue(any(), anyList())).thenReturn(CREDENTIALS);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-Forwarded-For", "198.51.100.9")
                        .header("CF-Connecting-IP", "198.51.100.10")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isOk());

        verify(rateLimiter).checkStrict("realtime:cred:ip:203.0.113.9", 60, Duration.ofMinutes(1));
    }

    @Test
    void configuredCloudflareHeaderIsUsed() throws Exception {
        properties.setTrustedClientIpHeader("CF-Connecting-IP");
        when(admissionService.issue(any(), anyList())).thenReturn(CREDENTIALS);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("CF-Connecting-IP", "203.0.113.7")
                        .header("X-Forwarded-For", "198.51.100.9")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk());

        verify(rateLimiter).checkStrict("realtime:cred:ip:203.0.113.7", 60, Duration.ofMinutes(1));
    }

    @Test
    void exceededRateLimitReturns429WithoutIssuing() throws Exception {
        doThrow(new RateLimitExceededException("too many requests"))
                .when(rateLimiter).checkStrict(anyString(), anyInt(), any());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));

        verify(admissionService, never()).issue(any(), anyList());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void failedRequiredRedisCheckReturnsBounded503WithoutAdmission(boolean accountCheck, boolean nullResult)
            throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var script = when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class)));
        if (accountCheck) {
            script = script.thenReturn(1L);
            User user = mock(User.class);
            when(user.getId()).thenReturn(7L);
            when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
            when(jwtService.validateAccessToken("good-token")).thenReturn(true);
            when(jwtService.extractUserId("good-token")).thenReturn("USR_7");
            when(userRepository.findByUserId("USR_7")).thenReturn(Optional.of(user));
        }
        if (nullResult) {
            script.thenReturn((Long) null);
        } else {
            script.thenThrow(new RedisConnectionFailureException("private-redis-detail"));
        }
        mvc = mvcUsing(new RateLimiter(redis));
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (accountCheck) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer good-token");
        }

        var response = mvc.perform(request)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("realtime credentials unavailable"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).hasSizeLessThan(200).doesNotContain("private-redis-detail");
        verify(admissionService, never()).issue(any(), anyList());
    }

    @Test
    void unavailableReturns503WithBoundedMessage() throws Exception {
        when(admissionService.issue(any(), anyList()))
                .thenThrow(new RealtimeUnavailableException("budget=0"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("realtime credentials unavailable"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void requestValidationErrorsReturn400() throws Exception {
        when(admissionService.issue(any(), anyList()))
                .thenThrow(new RealtimeRequestValidationException());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void twentyFiveDistinctIdsAreRejectedButADuplicateIsAccepted() throws Exception {
        List<String> distinct = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            distinct.add("p" + i);
        }
        when(admissionService.issue(any(), anyList()))
                .thenThrow(new RealtimeRequestValidationException());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(distinct)))
                .andExpect(status().isBadRequest());

        List<String> withDuplicate = new ArrayList<>(distinct.subList(0, 24));
        withDuplicate.add("p0");
        org.mockito.Mockito.doReturn(CREDENTIALS)
                .when(admissionService).issue(any(), anyList());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(withDuplicate)))
                .andExpect(status().isOk());
    }

    private static String body(List<String> productIds) {
        StringBuilder json = new StringBuilder("{\"productIds\":[");
        for (int i = 0; i < productIds.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(productIds.get(i)).append('"');
        }
        return json.append("]}").toString();
    }
}
