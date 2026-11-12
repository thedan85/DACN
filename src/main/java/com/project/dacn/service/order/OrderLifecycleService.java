package com.project.dacn.service.order;

import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.outbox.OrderEventOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private final OrderEventOutbox outbox;
    private final DatabaseClock clock;

    /**
     * Appends the immutable placement fact in the caller's transaction.
     * The order and payment must already be persisted so the fact records
     * durable state and the outbox row can reference the order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPlaced(Order order, Payment payment, String entryPoint) {
        if (order == null || order.getId() == null) {
            throw new IllegalStateException("order must be persisted before recording placement");
        }
        if (payment == null || payment.getId() == null) {
            throw new IllegalStateException("payment must be persisted before recording placement");
        }
        if (payment.getOrder() == null) {
            throw new IllegalStateException("payment must reference the order being recorded");
        }
        if (!order.getId().equals(payment.getOrder().getId())) {
            throw new IllegalStateException("payment does not belong to the order being recorded");
        }
        if (payment.getAmount() == null || order.getTotal() == null
                || payment.getAmount().compareTo(order.getTotal()) != 0) {
            throw new IllegalStateException("payment amount must match the order total");
        }
        if (order.getEventSequence() != 0) {
            throw new IllegalStateException("order already has a placement fact");
        }
        if (order.getCustomer() == null || order.getCustomer().getUser() == null) {
            throw new IllegalStateException("order customer must be persisted before recording placement");
        }

        OrderEvent event = OrderEvent.create(
                OrderEventType.ORDER_PLACED,
                order.getOrderId(),
                1L,
                clock.now(),
                order.getCustomer().getUser().getId(),
                order.getStatus(),
                payment.getStatus(),
                payment.getMethod(),
                order.getTotal(),
                null,
                UUID.randomUUID().toString(),
                entryPoint);
        outbox.append(order.getId(), event);
        order.setEventSequence(1L);
    }
}
