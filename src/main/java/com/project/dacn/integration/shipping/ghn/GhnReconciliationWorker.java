package com.project.dacn.integration.shipping.ghn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.entity.order.Order;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.service.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Webhook fallback: polls GHN for non-terminal orders. No transaction spans a GHN call. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "shipping.ghn.reconcile-enabled", havingValue = "true",
        matchIfMissing = true)
public class GhnReconciliationWorker {

    private static final List<OrderStatus> NON_TERMINAL = List.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPING);
    private static final int MAX_BATCH_SIZE = 500;

    private final GhnClient ghnClient;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Value("${shipping.ghn.reconcile-batch-size:50}")
    private int batchSize = 50;

    // Only the dedicated single-thread scheduler calls reconcile(), so no lock is needed.
    private long cursor;

    @Scheduled(fixedDelayString = "${shipping.ghn.reconcile-interval-ms:300000}",
            initialDelayString = "${shipping.ghn.reconcile-initial-delay-ms:30000}",
            scheduler = "ghnReconciliationScheduler")
    public void reconcile() {
        if (!ghnClient.isConfigured()) {
            return;
        }
        int size = Math.max(1, Math.min(batchSize, MAX_BATCH_SIZE));
        Pageable page = PageRequest.of(0, size);
        List<Order> candidates = orderRepository.findGhnReconciliationCandidates(NON_TERMINAL, cursor, page);
        for (Order order : candidates) {
            reconcileOne(order);
        }
        // Full batch advances past the last id; a short batch wraps so no candidate starves.
        cursor = candidates.size() == size ? candidates.get(candidates.size() - 1).getId() : 0L;
    }

    private void reconcileOne(Order order) {
        try {
            String status = ghnClient.getOrderStatus(order.getGhnOrderCode());
            GhnStatusMapper.Event event = GhnStatusMapper.map(status);
            if (event == GhnStatusMapper.Event.NONE) {
                return;
            }
            orderService.applyGhnEvent(order.getGhnOrderCode(), order.getOrderId(), event);
        } catch (RuntimeException error) {
            // Exception messages can carry GHN response data; log the bounded class only.
            log.warn("GHN reconciliation failed order={} failure={}",
                    order.getOrderId(), error.getClass().getSimpleName());
        }
    }
}
