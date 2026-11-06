package com.project.dacn.messaging.outbox;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.ThrowableAssert.ThrowingCallable;

@SpringBootTest
@ActiveProfiles("test")
class OrderEventCodecTest {

    @Autowired
    ObjectMapper mapper;

    @Test
    void roundTripsAValidEventThroughTheBootMapper() {
        OrderEventCodec codec = new OrderEventCodec(mapper);
        OrderEvent event = validEvent();

        OrderEvent decoded = codec.decode(codec.encode(event));

        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.occurredAt()).isEqualTo(Instant.parse("2026-11-03T10:15:30.123456Z"));
    }

    @Test
    void encodeWritesUtf8JsonWithIsoInstantAndNoTypeMetadata() {
        OrderEventCodec codec = new OrderEventCodec(mapper);
        OrderEvent event = validEvent();

        String json = new String(codec.encode(event), StandardCharsets.UTF_8);

        assertThat(json)
                .contains("\"schemaVersion\":1")
                .contains("\"eventType\":\"ORDER_PLACED\"")
                .contains("\"occurredAt\":\"2026-11-03T10:15:30.123456Z\"")
                .doesNotContain("@class")
                .doesNotContain("@type")
                .doesNotContain("email")
                .doesNotContain("address");
    }

    @Test
    void decodeIgnoresInjectedTypeMetadata() {
        OrderEventCodec codec = new OrderEventCodec(mapper);
        OrderEvent event = validEvent();
        String json = new String(codec.encode(event), StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\"@class\":\"java.lang.ProcessBuilder\",");

        assertThat(codec.decode(json.getBytes(StandardCharsets.UTF_8))).isEqualTo(event);
    }

    @Test
    void encodeRejectsEventsLargerThanSixteenKilobytes() {
        OrderEventCodec codec = new OrderEventCodec(mapper);
        OrderEvent event = OrderEvent.create(OrderEventType.ORDER_PLACED, "O".repeat(17000), 1L,
                Instant.parse("2026-11-03T10:15:30Z"), 42L, OrderStatus.PENDING,
                PaymentStatus.PENDING, PaymentMethod.COD, new BigDecimal("1.00"), null,
                "corr-123", "CHECKOUT_WEB");

        assertCode(() -> codec.encode(event), OrderEventCodecException.ReasonCode.EVENT_TOO_LARGE);
    }

    @Test
    void encodeAcceptsAnEventOfExactlySixteenKilobytes() {
        OrderEventCodec codec = new OrderEventCodec(mapper);

        byte[] payload = codec.encode(eventWithSerializedSize(16384));

        assertThat(payload).hasSize(16384);
    }

    @Test
    void encodeRejectsAnEventOfExactlyOneByteOverSixteenKilobytes() {
        OrderEventCodec codec = new OrderEventCodec(mapper);
        OrderEvent event = eventWithSerializedSize(16385);

        assertThat(mapper.writeValueAsBytes(event)).hasSize(16385);
        assertCode(() -> codec.encode(event), OrderEventCodecException.ReasonCode.EVENT_TOO_LARGE);
    }

    @Test
    void decodeAcceptsAValidEventOfExactlySixteenKilobytes() {
        OrderEventCodec codec = new OrderEventCodec(mapper);
        OrderEvent event = eventWithSerializedSize(16384);
        byte[] payload = codec.encode(event);

        // The size gate is "> 16384", so a valid 16384-byte event must decode.
        assertThat(payload).hasSize(16384);
        assertThat(codec.decode(payload)).isEqualTo(event);
    }

    @Test
    void decodeRejectsPayloadsLargerThanSixteenKilobytes() {
        OrderEventCodec codec = new OrderEventCodec(mapper);

        assertCode(() -> codec.decode("0".repeat(16385).getBytes(StandardCharsets.UTF_8)),
                OrderEventCodecException.ReasonCode.EVENT_TOO_LARGE);
    }

    @Test
    void decodeRejectsMalformedJsonWithABoundedReasonCode() {
        OrderEventCodec codec = new OrderEventCodec(mapper);

        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":1,".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(OrderEventCodecException.class, exception -> {
                    assertThat(exception.reasonCode())
                            .isEqualTo(OrderEventCodecException.ReasonCode.EVENT_INVALID);
                    assertThat(exception).hasMessage("EVENT_INVALID");
                });
    }

    @Test
    void decodeRejectsConstructorValidationFailuresAsInvalid() {
        OrderEventCodec codec = new OrderEventCodec(mapper);

        assertCode(() -> codec.decode(json("1", "ORDER_PLACED", null, "ORD_1", 0, "1.00")),
                OrderEventCodecException.ReasonCode.EVENT_INVALID);
        assertCode(() -> codec.decode(json("1", "ORDER_PLACED", null, "ORD_1", 1, "12.3")),
                OrderEventCodecException.ReasonCode.EVENT_INVALID);
        assertCode(() -> codec.decode("{}".getBytes(StandardCharsets.UTF_8)),
                OrderEventCodecException.ReasonCode.EVENT_INVALID);
    }

    @Test
    void decodeRejectsUnsupportedSchemaVersions() {
        OrderEventCodec codec = new OrderEventCodec(mapper);

        assertCode(() -> codec.decode(json("2", "ORDER_PLACED", null, "ORD_1", 1, "1.00")),
                OrderEventCodecException.ReasonCode.EVENT_UNSUPPORTED);
        assertCode(() -> codec.decode(json("2.0", "ORDER_PLACED", null, "ORD_1", 1, "1.00")),
                OrderEventCodecException.ReasonCode.EVENT_UNSUPPORTED);
        assertCode(() -> codec.decode(json("\"2\"", "ORDER_PLACED", null, "ORD_1", 1, "1.00")),
                OrderEventCodecException.ReasonCode.EVENT_UNSUPPORTED);
    }

    @Test
    void decodeRejectsUnknownEventTypes() {
        OrderEventCodec codec = new OrderEventCodec(mapper);

        assertCode(() -> codec.decode(json("1", "ORDER_ARCHIVED", null, "ORD_1", 1, "1.00")),
                OrderEventCodecException.ReasonCode.EVENT_UNSUPPORTED);
    }

    @Test
    void decodeRejectsUnknownReasons() {
        OrderEventCodec codec = new OrderEventCodec(mapper);

        assertCode(() -> codec.decode(json("1", "ORDER_REJECTED", "CUSTOMER_CANCELLED", "ORD_1", 1, "1.00")),
                OrderEventCodecException.ReasonCode.EVENT_UNSUPPORTED);
    }

    @Test
    void decodeRejectsAReasonThatDoesNotBelongToTheEventType() {
        OrderEventCodec codec = new OrderEventCodec(mapper);

        assertCode(() -> codec.decode(json("1", "ORDER_PLACED", "STAFF_REJECTED", "ORD_1", 1, "1.00")),
                OrderEventCodecException.ReasonCode.EVENT_INVALID);
    }

    @Test
    void decodeAcceptsTheExactSchema() {
        OrderEventCodec codec = new OrderEventCodec(mapper);

        OrderEvent event = codec.decode(json("1", "ORDER_REJECTED", "STAFF_REJECTED", "ORD_1", 3, "19.90"));

        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.eventId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(event.orderSequence()).isEqualTo(3L);
        assertThat(event.customerUserId()).isEqualTo("42");
        assertThat(event.reason()).isEqualTo(com.project.dacn.event.OrderEventReason.STAFF_REJECTED);
    }

    private static void assertCode(ThrowingCallable callable, OrderEventCodecException.ReasonCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(OrderEventCodecException.class, exception -> {
                    assertThat(exception.reasonCode()).isEqualTo(expected);
                    assertThat(exception).hasMessage(expected.name());
                });
    }

    private static OrderEvent validEvent() {
        return eventWithOrderId("ORD_123");
    }

    private static OrderEvent eventWithOrderId(String orderId) {
        return OrderEvent.create(OrderEventType.ORDER_PLACED, orderId, 1L,
                Instant.parse("2026-11-03T10:15:30.123456Z"), 42L, OrderStatus.PENDING,
                PaymentStatus.PENDING, PaymentMethod.COD, new BigDecimal("199.90"), null,
                "corr-123", "CHECKOUT_WEB");
    }

    private OrderEvent eventWithSerializedSize(int targetBytes) {
        int orderIdLength = "ORD_123".length();
        for (int attempt = 0; attempt < 4; attempt++) {
            OrderEvent candidate = eventWithOrderId("O".repeat(orderIdLength));
            int size = mapper.writeValueAsBytes(candidate).length;
            if (size == targetBytes) {
                return candidate;
            }
            orderIdLength += targetBytes - size;
        }
        throw new AssertionError("could not build an event of exactly " + targetBytes + " bytes");
    }

    private static byte[] json(String schemaVersion, String eventType, String reason,
            String orderId, long orderSequence, String total) {
        return """
                {"schemaVersion":%s,"eventId":"11111111-1111-1111-1111-111111111111",\
                "eventType":"%s","orderId":"%s","orderSequence":%d,\
                "occurredAt":"2026-11-03T10:15:30Z","customerUserId":"42",\
                "orderStatus":"PENDING","paymentStatus":"PENDING","paymentMethod":"COD",\
                "total":"%s","reason":%s,"correlationId":"corr-123","entryPoint":"CHECKOUT_WEB"}
                """.formatted(schemaVersion, eventType, orderId, orderSequence, total,
                reason == null ? "null" : "\"" + reason + "\"").getBytes(StandardCharsets.UTF_8);
    }
}
