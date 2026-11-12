package com.project.dacn.service.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.messaging.outbox.OrderEventOutbox;
import com.project.dacn.integration.payment.PaymentSettlementService.Result;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderItemRepository orderItemRepository;
    private final VariantRepository variantRepository;
    private final OrderResponseAssembler responseAssembler;
    private final OrderEventOutbox outbox;
    private final DatabaseClock clock;
    private final ReservationPolicy reservationPolicy;

    @PersistenceContext
    private EntityManager entityManager;

    private volatile Boolean transactionLockBounds;

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

    /** Staff acceptance is limited to a pending COD order whose cash payment is still due. */
    @Transactional
    public OrderResponse confirmCod(String orderId) {
        boundLockWait();
        Order order = lockOrder(orderId);
        requirePending(order);
        requireUnreleased(order);
        Payment payment = lockPayment(orderId);
        if (payment.getMethod() != PaymentMethod.COD) {
            throw new InvalidOrderStateException("online orders cannot be confirmed by staff");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new InvalidOrderStateException("payment is not pending: " + payment.getStatus());
        }

        Instant occurredAt = clock.now();
        order.setStatus(OrderStatus.CONFIRMED);
        append(order, payment, OrderEventType.ORDER_CONFIRMED, null,
                occurredAt, "STAFF_ACCEPT_COD");
        return responseAssembler.toResponse(order);
    }

    /**
     * Rejects a pending order exactly once: stock is restored before the
     * durable release marker, and the marker, status, payment and fact commit
     * in the same transaction.
     */
    @Transactional
    public OrderResponse reject(String orderId) {
        boundLockWait();
        Order order = lockOrder(orderId);
        requirePending(order);
        requireUnreleased(order);
        Payment payment = lockPayment(orderId);

        Instant occurredAt = clock.now();
        releaseStock(order, occurredAt,
                orderItemRepository.findStockReleaseTargetsByOrderId(orderId));
        order.setCancellationReason(OrderEventReason.STAFF_REJECTED);
        order.setStatus(OrderStatus.CANCELLED);
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.FAILED);
        }
        append(order, payment, OrderEventType.ORDER_REJECTED, OrderEventReason.STAFF_REJECTED,
                occurredAt, "STAFF_REJECT");
        return responseAssembler.toResponse(order);
    }

    /**
     * Admin manual completion. Online orders need received money; COD orders
     * stay eligible while their payment is due. Stock and payment are untouched.
     */
    @Transactional
    public OrderResponse complete(String orderId) {
        boundLockWait();
        Order order = lockOrder(orderId);
        OrderStatus status = order.getStatus();
        if (status != OrderStatus.PENDING
                && status != OrderStatus.CONFIRMED
                && status != OrderStatus.SHIPPING) {
            throw new InvalidOrderStateException(
                    "order cannot be completed from: " + status);
        }
        requireUnreleased(order);
        Payment payment = lockPayment(orderId);
        PaymentStatus paymentStatus = payment.getStatus();
        boolean eligible = payment.getMethod() == PaymentMethod.COD
                ? paymentStatus == PaymentStatus.PENDING || paymentStatus == PaymentStatus.CONFIRMED
                : paymentStatus == PaymentStatus.CONFIRMED;
        if (!eligible) {
            throw new InvalidOrderStateException(
                    "order payment cannot support completion: " + paymentStatus);
        }

        Instant occurredAt = clock.now();
        order.setStatus(OrderStatus.COMPLETED);
        append(order, payment, OrderEventType.ORDER_COMPLETED, null,
                occurredAt, "ADMIN_COMPLETE");
        return responseAssembler.toResponse(order);
    }

    /** Only verified provider callbacks may enter this order-first transaction. */
    @Transactional
    public Result settleOnline(String orderId, BigDecimal amount, boolean success, String transactionId) {
        boundLockWait();
        if (orderId == null || orderId.isBlank()) {
            return Result.NOT_FOUND;
        }
        Order order = orderRepository.findWithLockByOrderId(orderId).orElse(null);
        if (order == null) {
            return Result.NOT_FOUND;
        }
        Payment payment = paymentRepository.findWithLockByOrder_OrderId(orderId)
                .orElseThrow(() -> new ReservationReviewRequiredException(orderId));
        if (payment.getMethod() == PaymentMethod.COD) {
            return Result.NOT_FOUND;
        }
        if (amount == null || payment.getAmount() == null
                || payment.getAmount().compareTo(amount) != 0) {
            return Result.AMOUNT_MISMATCH;
        }
        if (success && (transactionId == null || transactionId.isBlank())) {
            return Result.NOT_FOUND;
        }
        PaymentStatus status = payment.getStatus();
        if (status == PaymentStatus.CONFIRMED || status == PaymentStatus.REFUND_REQUIRED) {
            // Never overwrite received-money evidence, even on a conflicting later callback.
            return Result.ALREADY_SETTLED;
        }
        if (payment.getMethod() == null || payment.getPaidAt() != null
                || payment.getTransactionId() != null
                || order.getTotal() == null || payment.getAmount().compareTo(order.getTotal()) != 0
                || (status != PaymentStatus.PENDING && status != PaymentStatus.FAILED)
                || (reservationPolicy.isActive() && order.getPaymentExpiresAt() == null)) {
            throw new ReservationReviewRequiredException(orderId);
        }
        List<OrderItemRepository.StockReleaseTarget> targets =
                orderItemRepository.findStockReleaseTargetsByOrderId(orderId);
        boolean reserved = order.getStatus() == OrderStatus.PENDING
                && status == PaymentStatus.PENDING && order.getStockReleasedAt() == null;
        boolean released = order.getStatus() == OrderStatus.CANCELLED
                && order.getStockReleasedAt() != null && order.getCancellationReason() != null;
        if (targets.isEmpty() || (!reserved && !released)) {
            throw new ReservationReviewRequiredException(orderId);
        }

        Instant occurredAt = clock.now();
        boolean expired = reserved && reservationPolicy.isActive()
                && !occurredAt.isBefore(order.getPaymentExpiresAt());
        if (expired || (reserved && !success)) {
            releaseStock(order, occurredAt, targets);
            order.setStatus(OrderStatus.CANCELLED);
            payment.setStatus(PaymentStatus.FAILED);
            OrderEventReason reason = expired ? OrderEventReason.PAYMENT_EXPIRED : OrderEventReason.PAYMENT_FAILED;
            order.setCancellationReason(reason);
            append(order, payment, expired ? OrderEventType.ORDER_EXPIRED : OrderEventType.PAYMENT_FAILED,
                    reason, occurredAt, "PAYMENT_CALLBACK");
        }
        if (!success) {
            return released ? Result.ALREADY_SETTLED : Result.SETTLED;
        }

        payment.setPaidAt(occurredAt);
        payment.setTransactionId(transactionId);
        if (released || expired) {
            payment.setStatus(PaymentStatus.REFUND_REQUIRED);
            append(order, payment, OrderEventType.REFUND_REQUIRED, null, occurredAt, "PAYMENT_CALLBACK");
            return Result.REFUND_REQUIRED;
        }
        payment.setStatus(PaymentStatus.CONFIRMED);
        order.setStatus(OrderStatus.CONFIRMED);
        append(order, payment, OrderEventType.ORDER_CONFIRMED, null, occurredAt, "PAYMENT_CALLBACK");
        return Result.SETTLED;
    }

    private void releaseStock(Order order, Instant occurredAt,
                              List<OrderItemRepository.StockReleaseTarget> targets) {
        requireUnreleased(order);
        for (OrderItemRepository.StockReleaseTarget target : targets) {
            Long quantity = target.getQuantity();
            if (quantity == null || quantity <= 0 || quantity > Integer.MAX_VALUE) {
                throw new IllegalStateException("invalid stock release quantity: " + quantity);
            }
            int updated = variantRepository.incrementStockNative(target.getVariantId(), quantity.intValue());
            if (updated != 1) {
                throw new IllegalStateException("stock target not found: " + target.getVariantId());
            }
        }
        order.setStockReleasedAt(occurredAt);
    }

    private Order lockOrder(String orderId) {
        return orderRepository.findWithLockByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "order not found: " + orderId));
    }

    private void boundLockWait() {
        if (transactionLockBounds == null) {
            transactionLockBounds = "PostgreSQL".equals(entityManager.unwrap(Session.class)
                    .doReturningWork(connection -> connection.getMetaData().getDatabaseProductName()));
        }
        if (transactionLockBounds) {
            // PostgreSQL ignores positive JPA lock hints; set the bound transaction-locally.
            entityManager.createNativeQuery("set local lock_timeout = '2s'").executeUpdate();
        }
    }

    private Payment lockPayment(String orderId) {
        return paymentRepository.findWithLockByOrder_OrderId(orderId)
                .orElseThrow(() -> new InvalidOrderStateException(
                        "payment not found for order: " + orderId));
    }

    private static void requirePending(Order order) {
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException(
                    "order is not pending: " + order.getStatus());
        }
    }

    private static void requireUnreleased(Order order) {
        if (order.getStockReleasedAt() != null) {
            throw new InvalidOrderStateException(
                    "order stock already released: " + order.getOrderId());
        }
    }

    private void append(Order order, Payment payment, OrderEventType eventType,
                        OrderEventReason reason, Instant occurredAt, String entryPoint) {
        long sequence = order.getEventSequence() + 1;
        OrderEvent event = OrderEvent.create(
                eventType,
                order.getOrderId(),
                sequence,
                occurredAt,
                order.getCustomer().getUser().getId(),
                order.getStatus(),
                payment.getStatus(),
                payment.getMethod(),
                order.getTotal(),
                reason,
                UUID.randomUUID().toString(),
                entryPoint);
        outbox.append(order.getId(), event);
        order.setEventSequence(sequence);
    }
}
