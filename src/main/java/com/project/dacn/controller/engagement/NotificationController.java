package com.project.dacn.controller.engagement;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.request.engagement.NotificationReadRequest;
import com.project.dacn.dto.response.engagement.NotificationCountResponse;
import com.project.dacn.dto.response.engagement.NotificationInboxResponse;
import com.project.dacn.dto.response.engagement.NotificationReadResponse;
import com.project.dacn.dto.response.engagement.NotificationUnreadCountResponse;
import com.project.dacn.service.engagement.NotificationInboxService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authorized inbox reads and owned read/delete mutations for the current principal. */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "notifications")
public class NotificationController {

    private final NotificationInboxService service;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationInboxResponse>> inbox(
            Authentication auth,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) NotificationStatus status) {
        validatePaging(page, size);
        return noStore(ApiResponse.ok(service.getInbox(principalId(auth), page, size, status)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<NotificationUnreadCountResponse>> unreadCount(
            Authentication auth) {
        return noStore(ApiResponse.ok(service.unreadCount(principalId(auth))));
    }

    @PatchMapping("/{notificationId}")
    public ResponseEntity<ApiResponse<NotificationReadResponse>> markRead(
            Authentication auth,
            @PathVariable String notificationId,
            @RequestBody String body) {
        NotificationReadRequest.parse(body);
        return noStore(ApiResponse.ok(service.markRead(principalId(auth), notificationId)));
    }

    @DeleteMapping("/{notificationId}")
    public ResponseEntity<ApiResponse<NotificationCountResponse>> delete(
            Authentication auth,
            @PathVariable String notificationId) {
        return noStore(ApiResponse.ok(service.delete(principalId(auth), notificationId)));
    }

    private static void validatePaging(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > NotificationInboxService.MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }

    /** The principal name is the immutable business user id set by the JWT filter. */
    private static String principalId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("authentication required");
        }
        return auth.getName();
    }

    private static <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
