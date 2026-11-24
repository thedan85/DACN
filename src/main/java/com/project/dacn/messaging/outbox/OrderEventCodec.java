package com.project.dacn.messaging.outbox;

import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.event.OrderEventValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderEventCodec {

    static final int MAX_EVENT_BYTES = 16384;

    private final ObjectMapper mapper;

    public OrderEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] encode(OrderEvent event) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(event);
        } catch (JacksonException e) {
            throw new OrderEventCodecException(OrderEventCodecException.ReasonCode.EVENT_INVALID);
        }
        if (payload.length > MAX_EVENT_BYTES) {
            throw new OrderEventCodecException(OrderEventCodecException.ReasonCode.EVENT_TOO_LARGE);
        }
        return payload;
    }

    public OrderEvent decode(byte[] payload) {
        if (payload == null) {
            throw invalid();
        }
        if (payload.length > MAX_EVENT_BYTES) {
            throw new OrderEventCodecException(OrderEventCodecException.ReasonCode.EVENT_TOO_LARGE);
        }
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || !root.isObject()) {
                throw invalid();
            }
            if (unsupportedSchemaVersion(root)
                    || unknownEnumValue(root, "eventType", OrderEventType.class)
                    || unknownEnumValue(root, "reason", OrderEventReason.class)) {
                throw new OrderEventCodecException(
                        OrderEventCodecException.ReasonCode.EVENT_UNSUPPORTED);
            }
            return mapper.readerFor(OrderEvent.class).readValue(payload);
        } catch (OrderEventCodecException e) {
            throw e;
        } catch (OrderEventValidationException | JacksonException e) {
            throw invalid();
        }
    }

    private static OrderEventCodecException invalid() {
        return new OrderEventCodecException(OrderEventCodecException.ReasonCode.EVENT_INVALID);
    }

    private static boolean unsupportedSchemaVersion(JsonNode root) {
        JsonNode version = root.get("schemaVersion");
        if (version == null || version.isNull()) {
            return false;
        }
        if (version.isNumber() && !version.isIntegralNumber()) {
            return true;
        }
        if (version.isIntegralNumber()) {
            return version.asInt() != 1;
        }
        if (version.isString()) {
            try {
                return Integer.parseInt(version.asString()) != 1;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean unknownEnumValue(JsonNode root, String field, Class<? extends Enum<?>> type) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || !value.isString()) {
            return false;
        }
        String name = value.asString();
        for (Enum<?> constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return false;
            }
        }
        return true;
    }
}
