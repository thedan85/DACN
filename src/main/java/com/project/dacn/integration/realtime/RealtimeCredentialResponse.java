package com.project.dacn.integration.realtime;

import java.time.Instant;
import java.util.List;

public record RealtimeCredentialResponse(String accessToken, Instant expiresAt,
                                         List<String> admittedProductIds, String inboxTopic) {

    public RealtimeCredentialResponse {
        admittedProductIds = List.copyOf(admittedProductIds);
    }
}
