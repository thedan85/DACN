package com.project.dacn.integration.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RealtimeAdmissionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ControlRow lockControl() {
        return jdbcTemplate.query("""
                select enabled, remaining_message_budget from app_realtime_control where id = 1 for update
                """, (row, index) -> new ControlRow(row.getBoolean("enabled"), row.getLong("remaining_message_budget")))
                .stream().findFirst().orElse(null);
    }

    public Set<String> findActiveProductIds(List<String> requestedIds) {
        if (requestedIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(requestedIds.size(), "?"));
        return new HashSet<>(jdbcTemplate.queryForList("""
                select product_id from products where product_status = 'ACTIVE' and product_id in (%s)
                """.formatted(placeholders), String.class, requestedIds.toArray()));
    }

    public Map<String, Long> countUnretiredStockLeases(List<String> productIds, Instant now) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        String containment = String.join(" or ", Collections.nCopies(productIds.size(),
                "leases.admitted_product_ids @> ?::jsonb"));
        String placeholders = String.join(", ", Collections.nCopies(productIds.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(Timestamp.from(now));
        for (String productId : productIds) {
            parameters.add(objectMapper.writeValueAsString(List.of(productId)));
        }
        parameters.addAll(productIds);
        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query("""
                select admitted.product_id, count(distinct leases.jti) as lease_count
                  from realtime_token_leases leases
                  cross join lateral jsonb_array_elements_text(leases.admitted_product_ids) as admitted(product_id)
                 where leases.retire_at > ?
                   and (%s)
                   and admitted.product_id in (%s)
                 group by admitted.product_id
                """.formatted(containment, placeholders), row -> {
                    counts.put(row.getString("product_id"), row.getLong("lease_count"));
                }, parameters.toArray());
        return counts;
    }

    public long countUnretiredInboxLeases(long userId, Instant now) {
        return jdbcTemplate.queryForObject("""
                select count(*) from realtime_token_leases
                 where user_id = ? and inbox_allowed and retire_at > ?
                """, Long.class, userId, Timestamp.from(now));
    }

    public void insertLease(UUID jti, Long userId, List<String> productIds, boolean inboxAllowed,
                            Instant expiresAt, Instant retireAt) {
        jdbcTemplate.update("""
                insert into realtime_token_leases
                    (jti, user_id, admitted_product_ids, inbox_allowed, expires_at, retire_at)
                values (?, ?, ?::jsonb, ?, ?, ?)
                """, jti, userId, objectMapper.writeValueAsString(productIds), inboxAllowed,
                Timestamp.from(expiresAt), Timestamp.from(retireAt));
    }

    public record ControlRow(boolean enabled, long remainingMessageBudget) {
    }
}
