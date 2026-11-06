package com.project.dacn.messaging.outbox;

import com.project.dacn.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;

@Component
@RequiredArgsConstructor
public class OrderEventOutbox {

    private final JdbcTemplate jdbcTemplate;
    private final OrderEventCodec codec;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long orderPk, OrderEvent event) {
        jdbcTemplate.update("""
                insert into event_outbox
                    (event_id, order_id, order_sequence, event_type,
                     schema_version, payload, available_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                event.eventId(), orderPk, event.orderSequence(),
                event.eventType().name(), event.schemaVersion(),
                new String(codec.encode(event), StandardCharsets.UTF_8),
                Timestamp.from(event.occurredAt()));
    }
}
