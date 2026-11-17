package com.project.dacn.dto.request.engagement;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Request-local strict decoder for the READ mutation. Parsing happens with a
 * private mapper, so only an object with exactly one {@code status: "READ"}
 * field is accepted. Global Jackson settings and the generic notification
 * create/update DTOs stay untouched.
 */
public record NotificationReadRequest(String status) {

    public static final String READ_VALUE = "READ";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public static NotificationReadRequest parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("notification read request body is required");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid notification read request", ex);
        }
        if (node == null || !node.isObject() || node.size() != 1) {
            throw new IllegalArgumentException("body must contain only status");
        }
        JsonNode status = node.get("status");
        if (status == null || !status.isString() || !READ_VALUE.equals(status.asString())) {
            throw new IllegalArgumentException("status must be READ");
        }
        return new NotificationReadRequest(READ_VALUE);
    }
}
