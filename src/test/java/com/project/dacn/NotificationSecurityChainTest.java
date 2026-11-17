package com.project.dacn;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.config.SecurityConfig;
import com.project.dacn.controller.engagement.NotificationController;
import com.project.dacn.security.AuthenticatedUser;
import com.project.dacn.security.AuthenticationEntryPoint;
import com.project.dacn.security.CustomAccessDeniedHandler;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import com.project.dacn.security.Permissions;
import com.project.dacn.service.engagement.NotificationInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Spring Security filter chain: JWT -> principal -> controller identity. */
@WebMvcTest(controllers = NotificationController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, AuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
class NotificationSecurityChainTest {

    private static final String PATH = "/api/v1/notifications";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @MockitoBean
    private NotificationInboxService service;

    @Test
    void missingBearerIs401WithEnvelope() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        verifyNoInteractions(service);
    }

    @Test
    void validBearerResolvesPrincipalBusinessIdFromUserDetails() throws Exception {
        authenticate("good-token", "USR_1", true, true, List.of(Permissions.ORDER_READ));
        when(service.getInbox("USR_1", 0, 20, null))
                .thenReturn(new com.project.dacn.dto.response.engagement.NotificationInboxResponse(
                        new com.project.dacn.common.response.PageResponse<>(List.of(), 0, 20, 0L, 0),
                        0L, 0L));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andExpect(jsonPath("$.data.revision").value(0));

        verify(service).getInbox("USR_1", 0, 20, null);
    }

    @Test
    void lockedOrDisabledAccountTokenIs401EvenWithValidSignature() throws Exception {
        authenticate("locked-token", "USR_1", true, false, List.of(Permissions.ORDER_READ));
        authenticate("disabled-token", "USR_1", false, true, List.of(Permissions.ORDER_READ));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer locked-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer disabled-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void revokedCompanyAuthorityIs403OnOwnedMutation() throws Exception {
        // Token claims order:read, but the freshly loaded user details no longer do.
        authenticate("stale-token", "USR_STALE", true, true, List.of());
        when(service.markRead("USR_STALE", "NOTI_1"))
                .thenThrow(new AccessDeniedException("current company authority is required"));

        mockMvc.perform(patch(PATH + "/NOTI_1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer stale-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verify(service).markRead("USR_STALE", "NOTI_1");
    }

    @Test
    void validBearerStillCannotSupplyTargetUserId() throws Exception {
        authenticate("good-token", "USR_1", true, true, List.of());
        when(service.unreadCount("USR_1"))
                .thenReturn(new com.project.dacn.dto.response.engagement.NotificationUnreadCountResponse(0L, 0L));

        mockMvc.perform(get(PATH + "/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .param("userId", "USR_2"))
                .andExpect(status().isOk());

        verify(service).unreadCount("USR_1");
    }

    @Test
    void notificationStatusQueryIsStillValidatedThroughChain() throws Exception {
        authenticate("good-token", "USR_1", true, true, List.of());

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .param("status", "READ")
                        .param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(service);
    }

    private void authenticate(String token, String businessId, boolean enabled,
                              boolean nonLocked, List<String> permissions) {
        when(jwtService.validateAccessToken(token)).thenReturn(true);
        when(jwtService.extractUserId(token)).thenReturn(businessId);
        AuthenticatedUser user = new AuthenticatedUser(businessId, "u@test.invalid", "Test", null,
                null, "EMPLOYEE", "Employee", permissions, false, null, "", enabled, nonLocked);
        when(userDetailsService.loadUserByUserId(businessId)).thenReturn(user);
    }
}
