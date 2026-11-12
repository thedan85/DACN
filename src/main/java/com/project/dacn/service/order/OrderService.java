package com.project.dacn.service.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.order.StaffOrderStatusUpdateRequest;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.order.Order;
import com.project.dacn.repository.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private static final int MAX_ORDER_SEARCH_LENGTH = 100;

    private final OrderRepository orderRepository;
    private final OrderResponseAssembler responseAssembler;
    private final OrderLifecycleService orderLifecycleService;

    public PageResponse<OrderResponse> list(Long customerId, OrderStatus status, String q,
                                            Instant createdAfter, Pageable pageable) {
        String query = q == null ? "" : q.trim();
        if (query.length() > MAX_ORDER_SEARCH_LENGTH) {
            throw new IllegalArgumentException(
                    "order search query must be at most " + MAX_ORDER_SEARCH_LENGTH + " characters");
        }
        Page<Order> page = orderRepository.searchForCustomer(
                customerId, query, status, createdAfter, pageable);
        return PageResponse.from(page, responseAssembler.toResponses(page.getContent()));
    }

    public OrderResponse get(Long customerId, String orderId) {
        Order order = orderRepository.findWithCustomerByOrderIdAndCustomer_Id(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "order not found: " + orderId));
        return responseAssembler.toResponse(order);
    }

    /**
     * Shop owner/employee view: every order, newest first, optionally filtered
     * by status and a keyword matching the order id or customer contact details.
     */
    public PageResponse<OrderResponse> listForStaff(OrderStatus status, String q, Pageable pageable) {
        String query = q == null ? "" : q.trim();
        Page<Order> page = orderRepository.searchForStaff(query, status, pageable);
        return PageResponse.from(page, responseAssembler.toResponses(page.getContent()));
    }

    /**
     * Accept (CONFIRMED) or reject (CANCELLED) a pending order. The shared
     * lifecycle service serializes the decision with payment settlement,
     * restores stock once on rejection, and records the lifecycle fact.
     */
    @Transactional
    public OrderResponse updateStatusByStaff(String orderId, StaffOrderStatusUpdateRequest request) {
        OrderStatus target = request == null ? null : request.status();
        if (target != OrderStatus.CONFIRMED && target != OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("staff can only set CONFIRMED or CANCELLED");
        }
        return target == OrderStatus.CONFIRMED
                ? orderLifecycleService.confirmCod(orderId)
                : orderLifecycleService.reject(orderId);
    }

    /**
     * Admin override: mark an eligible non-terminal order completed manually.
     * The shared lifecycle keeps unpaid online orders ineligible; COD orders
     * remain allowed and keep their payment and stock unchanged.
     */
    @Transactional
    public OrderResponse completeByAdmin(String orderId) {
        return orderLifecycleService.complete(orderId);
    }
}
