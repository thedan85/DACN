package com.project.dacn.controller.realtime;

import com.project.dacn.common.exception.RateLimitUnavailableException;
import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.request.realtime.RealtimeCredentialRequest;
import com.project.dacn.integration.realtime.RealtimeAdmissionService;
import com.project.dacn.integration.realtime.RealtimeCredentialAuthentication;
import com.project.dacn.integration.realtime.RealtimeCredentialResponse;
import com.project.dacn.integration.realtime.RealtimePrincipal;
import com.project.dacn.integration.realtime.RealtimeUnavailableException;
import com.project.dacn.security.RateLimiter;
import com.project.dacn.security.TrustedClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/realtime")
@RequiredArgsConstructor
public class RealtimeCredentialController {

    private static final int IP_LIMIT_PER_MINUTE = 60;
    private static final int USER_LIMIT_PER_MINUTE = 6;

    private final RealtimeCredentialAuthentication authentication;
    private final TrustedClientIpResolver clientIpResolver;
    private final RateLimiter rateLimiter;
    private final RealtimeAdmissionService admissionService;

    @PostMapping("/credentials")
    public ResponseEntity<ApiResponse<RealtimeCredentialResponse>> credentials(
            HttpServletRequest request,
            @Valid @RequestBody RealtimeCredentialRequest body) {
        RealtimePrincipal principal = authentication.resolve(request);
        try {
            rateLimiter.checkStrict("realtime:cred:ip:" + clientIpResolver.resolve(request),
                    IP_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
            if (principal.authenticated()) {
                rateLimiter.checkStrict("realtime:cred:user:" + principal.userId(),
                        USER_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
            }
        } catch (RateLimitUnavailableException ex) {
            throw new RealtimeUnavailableException("rate limiter unavailable");
        }
        RealtimeCredentialResponse credentials =
                admissionService.issue(principal, body.productIds());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(credentials));
    }
}
