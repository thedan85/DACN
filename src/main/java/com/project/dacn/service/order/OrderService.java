package com.project.dacn.service.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.order.StaffOrderStatusUpdateRequest;
import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper orderMapper;
    private final VariantRepository variantRepository;
    private final PaymentRepository paymentRepository;

    public List<OrderResponse> list(Long customerId) {
        return orderRepository.findByCustomer_IdOrderByIdDesc(customerId).stream()
                .map(this::toResponse)
                .toList();
    }

    public OrderResponse get(Long customerId, String orderId) {
        Order order = orderRepository.findByOrderIdAndCustomer_Id(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("order not found: " + orderId));
        return toResponse(order);
    }

    /**
     * Shop owner/employee view: every order, newest first, optionally filtered
     * by status.
     */
    public PageResponse<OrderResponse> listForStaff(OrderStatus status, Pageable pageable) {
        Page<Order> page = status == null
                ? orderRepository.findAllByOrderByIdDesc(pageable)
                : orderRepository.findByStatusOrderByIdDesc(status, pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toResponse).toList());
    }

    /**
     * Accept (CONFIRMED) or reject (CANCELLED) a pending order.
     *
     * <p>Locks the order row so the decision is serialized with VNPay IPN
     * settlement. Rejecting restores stock and fails an unpaid payment.</p>
     */
    @Transactional
    public OrderResponse updateStatusByStaff(String orderId, StaffOrderStatusUpdateRequest request) {
        OrderStatus target = request == null ? null : request.status();
        if (target != OrderStatus.CONFIRMED && target != OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("staff can only set CONFIRMED or CANCELLED");
        }
        Order order = orderRepository.findWithLockByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("order not found: " + orderId));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("order is not pending: " + order.getStatus());
        }
        order.setStatus(target);
        if (target == OrderStatus.CANCELLED) {
            restock(order);
            failPendingPayment(orderId);
            // settle() clears the persistence context, detaching the lazy
            // customer proxy; reload before mapping the response.
            order = orderRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "order not found: " + orderId));
        }
        return toResponse(order);
    }

    /**
     * Admin override: mark a non-terminal order completed manually. The normal
     * shipping flow is not implemented, so this is the only path to COMPLETED.
     * Stock and payment state are left unchanged.
     */
    @Transactional
    public OrderResponse completeByAdmin(String orderId) {
        Order order = orderRepository.findWithLockByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("order not found: " + orderId));
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.COMPLETED) {
            throw new InvalidOrderStateException(
                    "order cannot be completed from: " + order.getStatus());
        }
        order.setStatus(OrderStatus.COMPLETED);
        return toResponse(order);
    }

    private void restock(Order order) {
        for (OrderItem item : orderItemRepository.findByOrder_OrderId(order.getOrderId())) {
            variantRepository.incrementStock(item.getVariant().getId(), item.getQuantity());
        }
    }

    private void failPendingPayment(String orderId) {
        paymentRepository.findByOrder_OrderId(orderId)
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
                .ifPresent(payment -> paymentRepository.settle(payment.getPaymentId(),
                        PaymentStatus.PENDING, PaymentStatus.FAILED, null, Instant.now()));
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = orderItemRepository
                .findByOrder_OrderId(order.getOrderId()).stream()
                .map(orderMapper::toOrderItemResponse)
                .toList();
        return orderMapper.toOrderResponse(order, items);
    }
}
