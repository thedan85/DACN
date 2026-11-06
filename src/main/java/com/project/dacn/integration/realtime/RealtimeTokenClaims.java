package com.project.dacn.integration.realtime;

import java.util.List;

public record RealtimeTokenClaims(Long userId, List<String> productIds, boolean inboxAllowed) {

    public RealtimeTokenClaims {
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
    }
}
