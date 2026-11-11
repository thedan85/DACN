package com.project.dacn.integration.shipping.ghn;

import com.project.dacn.integration.shipping.ghn.dto.GhnWebhookPayload;
import com.project.dacn.service.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class GhnWebhookService {

    private final OrderService orderService;
    private final GhnProperties properties;

    public void handle(GhnWebhookPayload payload) {
        if (!"switch_status".equals(payload.type())) {
            log.debug("Ignoring GHN webhook type {}", payload.type());
            return;
        }
        if (payload.shopId() == null
                || !String.valueOf(payload.shopId()).equals(properties.getShopId())) {
            log.warn("Ignoring GHN webhook for shop {}", payload.shopId());
            return;
        }
        GhnStatusMapper.Event event = GhnStatusMapper.map(payload.status());
        log.info("GHN webhook order={} status={} event={}",
                payload.orderCode(), payload.status(), event);
        orderService.applyGhnEvent(payload.orderCode(), payload.clientOrderCode(), event);
    }
}
