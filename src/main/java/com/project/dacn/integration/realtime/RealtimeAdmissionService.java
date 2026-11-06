package com.project.dacn.integration.realtime;

import com.project.dacn.common.time.DatabaseClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RealtimeAdmissionService {

    private final RealtimeProperties properties;
    private final SupabaseRealtimeTokenService tokenService;
    private final DatabaseClock clock;
    private final RealtimeAdmissionRepository repository;

    @Transactional
    public RealtimeCredentialResponse issue(RealtimePrincipal principal, List<String> requestedProductIds) {
        List<String> ids = dedupeAndValidate(requestedProductIds);
        Objects.requireNonNull(principal, "principal");
        if (!properties.isEnabled()) {
            throw new RealtimeUnavailableException("realtime disabled");
        }
        RealtimeAdmissionRepository.ControlRow control = repository.lockControl();
        if (control == null || !control.enabled() || control.remainingMessageBudget() <= 0) {
            throw new RealtimeUnavailableException("realtime unavailable");
        }
        Instant now = clock.now();
        Set<String> activeIds = repository.findActiveProductIds(ids);
        List<String> visibleIds = ids.stream().filter(activeIds::contains).toList();
        Map<String, Long> counts = repository.countUnretiredStockLeases(visibleIds, now);
        int receiverCap = Math.max(0, Math.min(20, properties.getMaxReceiversPerTopic()));
        int stockCap = Math.max(0, Math.min(24, properties.getMaxStockTopics()));
        List<String> admitted = visibleIds.stream()
                .filter(id -> counts.getOrDefault(id, 0L) < receiverCap)
                .limit(stockCap)
                .toList();
        boolean inboxAllowed = principal.authenticated()
                && repository.countUnretiredInboxLeases(principal.userId(), now) < receiverCap;
        RealtimeToken token = tokenService.mint(new RealtimeTokenClaims(principal.userId(), admitted, inboxAllowed));
        repository.insertLease(token.jti(), principal.userId(), admitted, inboxAllowed,
                token.expiresAt(), token.expiresAt().plusSeconds(30));
        return new RealtimeCredentialResponse(token.accessToken(), token.expiresAt(), admitted,
                inboxAllowed ? "inbox:" + principal.userId() : null);
    }

    private List<String> dedupeAndValidate(List<String> requested) {
        if (requested == null) {
            throw new RealtimeRequestValidationException();
        }
        int requestCap = Math.max(0, Math.min(24, properties.getMaxRequestedProducts()));
        Set<String> unique = new LinkedHashSet<>();
        for (String id : requested) {
            if (id == null || id.isBlank() || id.length() > 255) {
                throw new RealtimeRequestValidationException();
            }
            unique.add(id);
            if (unique.size() > requestCap) {
                throw new RealtimeRequestValidationException();
            }
        }
        return List.copyOf(unique);
    }
}
