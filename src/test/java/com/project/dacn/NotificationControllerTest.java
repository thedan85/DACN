package com.project.dacn;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.common.constant.NotificationType;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.config.SecurityConfig;
import com.project.dacn.controller.engagement.NotificationController;
import com.project.dacn.dto.response.engagement.NotificationCountResponse;
import com.project.dacn.dto.response.engagement.NotificationInboxResponse;
import com.project.dacn.dto.response.engagement.NotificationReadResponse;
import com.project.dacn.dto.response.engagement.NotificationResponse;
import com.project.dacn.dto.response.engagement.NotificationUnreadCountResponse;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.AuthenticationEntryPoint;
import com.project.dacn.security.CustomAccessDeniedHandler;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import com.project.dacn.service.engagement.NotificationInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract, validation and envelope tests for the authorized notification inbox. */
@WebMvcTest(controllers = NotificationController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, AuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
class NotificationControllerTest {

    private static final String PATH = "/api/v1/notifications";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationInboxService service;

    // The WebMvcTest slice still constructs the filter bean; satisfy its constructor.
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void getInboxReturnsEnvelopeAdditiveFieldsAndNoStore() throws Exception {
        when(service.getInbox("USR_1", 0, 20, NotificationStatus.UNREAD)).thenReturn(inbox(false));

        mockMvc.perform(get(PATH).with(principal())
                        .param("status", "UNREAD"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.notifications.content[0].notificationId").value("NOTI_1"))
                .andExpect(jsonPath("$.data.notifications.content[0].status").value("UNREAD"))
                .andExpect(jsonPath("$.data.notifications.content[0].orderId").value("ORD_1"))
                .andExpect(jsonPath("$.data.notifications.content[0].eventType").value("ORDER_PLACED"))
                .andExpect(jsonPath("$.data.notifications.content[0].orderSequence").value(3))
                .andExpect(jsonPath("$.data.notifications.content[0].occurredAt")
                        .value("2026-11-11T01:00:00Z"))
                .andExpect(jsonPath("$.data.notifications.page").value(0))
                .andExpect(jsonPath("$.data.notifications.size").value(20))
                .andExpect(jsonPath("$.data.unreadCount").value(4))
                .andExpect(jsonPath("$.data.revision").value(7))
                .andExpect(jsonPath("$.data").value(aMapWithSize(3)));

        verify(service).getInbox("USR_1", 0, 20, NotificationStatus.UNREAD);
    }

    @Test
    void legacyRowExposesNullAdditiveFieldsAndCreationTimeIsNotOccurrenceTime() throws Exception {
        when(service.getInbox("USR_1", 0, 20, null)).thenReturn(inbox(true));

        mockMvc.perform(get(PATH).with(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications.content[0].createdAt")
                        .value("2026-11-10T00:00:00Z"))
                .andExpect(jsonPath("$.data.notifications.content[0].occurredAt")
                        .value("2026-11-10T00:00:00Z"))
                .andExpect(jsonPath("$.data.notifications.content[0].orderId").value(nullValue()))
                .andExpect(jsonPath("$.data.notifications.content[0].eventType").value(nullValue()))
                .andExpect(jsonPath("$.data.notifications.content[0].orderSequence").value(nullValue()));
    }

    @Test
    void getInboxUsesPrincipalIdentityNotSuppliedUserId() throws Exception {
        when(service.getInbox("USR_1", 1, 50, NotificationStatus.READ)).thenReturn(inbox(false));

        mockMvc.perform(get(PATH).with(principal())
                        .param("page", "1").param("size", "50")
                        .param("status", "READ")
                        .param("userId", "USR_2"))
                .andExpect(status().isOk());

        verify(service).getInbox("USR_1", 1, 50, NotificationStatus.READ);
    }

    @Test
    void getInboxRejectsInvalidPageSizeAndStatus() throws Exception {
        String[][] invalid = {
                {"page", "-1"}, {"size", "0"}, {"size", "101"},
                {"size", "abc"}, {"status", "BOGUS"}, {"status", "read"}
        };
        for (String[] pair : invalid) {
            mockMvc.perform(get(PATH).with(principal()).param(pair[0], pair[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void getInboxAcceptsMaximumPageSize() throws Exception {
        when(service.getInbox("USR_1", 0, 100, null)).thenReturn(inbox(false));

        mockMvc.perform(get(PATH).with(principal()).param("size", "100"))
                .andExpect(status().isOk());

        verify(service).getInbox("USR_1", 0, 100, null);
    }

    @Test
    void unreadCountReturnsBellEnvelope() throws Exception {
        when(service.unreadCount("USR_1")).thenReturn(new NotificationUnreadCountResponse(4L, 7L));

        mockMvc.perform(get(PATH + "/unread-count").with(principal()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.unreadCount").value(4))
                .andExpect(jsonPath("$.data.revision").value(7))
                .andExpect(jsonPath("$.data").value(aMapWithSize(2)));

        verify(service).unreadCount("USR_1");
    }

    @Test
    void unreadCountRejectsTargetUserIdParameter() throws Exception {
        when(service.unreadCount("USR_1")).thenReturn(new NotificationUnreadCountResponse(0L, 0L));

        mockMvc.perform(get(PATH + "/unread-count").with(principal())
                        .param("userId", "USR_2"))
                .andExpect(status().isOk());

        verify(service).unreadCount("USR_1");
    }

    @Test
    void patchMarksReadWithExactBodyAndNoStore() throws Exception {
        when(service.markRead("USR_1", "NOTI_1")).thenReturn(read());

        mockMvc.perform(patch(PATH + "/NOTI_1").with(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.notification.notificationId").value("NOTI_1"))
                .andExpect(jsonPath("$.data.notification.status").value("READ"))
                .andExpect(jsonPath("$.data.unreadCount").value(3))
                .andExpect(jsonPath("$.data.revision").value(8))
                .andExpect(jsonPath("$.data").value(aMapWithSize(3)));

        verify(service).markRead("USR_1", "NOTI_1");
    }

    @Test
    void patchRejectsUnsupportedBodies() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"status\":null}",
                "{\"status\":\"UNREAD\"}",
                "{\"status\":\"read\"}",
                "{\"status\":\"READ \"}",
                "{\"status\":\"READ\",\"message\":\"hacked\"}",
                "{\"status\":\"READ\",\"userId\":\"USR_2\"}",
                "{\"status\":\"READ\",\"deleted\":false}",
                "{\"status\":\"READ\",\"orderId\":\"ORD_9\"}",
                "{\"status\":\"READ\",\"status\":\"UNREAD\"}",
                "{\"status\":\"UNREAD\",\"status\":\"READ\"}",
                "",
                "not-json")) {
            mockMvc.perform(patch(PATH + "/NOTI_1").with(principal())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void patchMissingForeignOrDeletedForReadReturns404() throws Exception {
        when(service.markRead(any(), eq("NOTI_1")))
                .thenThrow(new ResourceNotFoundException("notification not found"));

        mockMvc.perform(patch(PATH + "/NOTI_1").with(principal())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READ\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void patchLostCompanyAuthorityReturns403() throws Exception {
        when(service.markRead(any(), eq("NOTI_1")))
                .thenThrow(new AccessDeniedException("current company authority is required"));

        mockMvc.perform(patch(PATH + "/NOTI_1").with(principal())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READ\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void deleteReturnsCountEnvelopeAndNoStore() throws Exception {
        when(service.delete("USR_1", "NOTI_1")).thenReturn(new NotificationCountResponse(3L, 9L));

        mockMvc.perform(delete(PATH + "/NOTI_1").with(principal()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.unreadCount").value(3))
                .andExpect(jsonPath("$.data.revision").value(9))
                .andExpect(jsonPath("$.data").value(aMapWithSize(2)));

        verify(service).delete("USR_1", "NOTI_1");
    }

    @Test
    void repeatedOwnedDeleteSucceedsWithoutAnotherRevision() throws Exception {
        when(service.delete("USR_1", "NOTI_1")).thenReturn(new NotificationCountResponse(3L, 9L));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete(PATH + "/NOTI_1").with(principal()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.revision").value(9));
        }

        verify(service, times(2)).delete("USR_1", "NOTI_1");
    }

    @Test
    void deleteMissingOrForeignReturns404() throws Exception {
        when(service.delete(any(), eq("NOTI_X")))
                .thenThrow(new ResourceNotFoundException("notification not found"));

        mockMvc.perform(delete(PATH + "/NOTI_X").with(principal()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service);
    }

    private static RequestPostProcessor principal() {
        return SecurityMockMvcRequestPostProcessors.user("USR_1");
    }

    private static NotificationInboxResponse inbox(boolean legacy) {
        Instant occurred = legacy
                ? Instant.parse("2026-11-10T00:00:00Z")
                : Instant.parse("2026-11-11T01:00:00Z");
        NotificationResponse row = new NotificationResponse(
                "NOTI_1", "USR_1", "message", NotificationType.ORDER,
                NotificationStatus.UNREAD,
                Instant.parse("2026-11-10T00:00:00Z"),
                Instant.parse("2026-11-11T00:00:00Z"),
                legacy ? null : "ORD_1",
                legacy ? null : "ORDER_PLACED",
                legacy ? null : 3L,
                occurred);
        return new NotificationInboxResponse(
                new PageResponse<>(List.of(row), 0, 20, 1, 1), 4L, 7L);
    }

    private static NotificationReadResponse read() {
        NotificationResponse row = new NotificationResponse(
                "NOTI_1", "USR_1", "message", NotificationType.ORDER,
                NotificationStatus.READ,
                Instant.parse("2026-11-10T00:00:00Z"),
                Instant.parse("2026-11-11T02:00:00Z"),
                "ORD_1", "ORDER_PLACED", 3L, Instant.parse("2026-11-11T01:00:00Z"));
        return new NotificationReadResponse(row, 3L, 8L);
    }
}
