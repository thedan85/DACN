package com.project.dacn.service.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.order.StaffOrderStatusUpdateRequest;
import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;
    private final VariantRepository variantRepository;
    private final VariantAttributeValueRepository variantAttributeValueRepository;
    private final MediaRepository mediaRepository;
    private final PaymentRepository paymentRepository;

    public PageResponse<OrderResponse> list(Long customerId, Pageable pageable) {
        Page<Order> page = orderRepository.findByCustomer_IdOrderByIdDesc(customerId, pageable);
        return PageResponse.from(page, toResponses(page.getContent()));
    }

    public OrderResponse get(Long customerId, String orderId) {
        Order order = orderRepository.findWithCustomerByOrderIdAndCustomer_Id(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("order not found: " + orderId));
        return toResponse(order);
    }

    /**
     * Shop owner/employee view: every order, newest first, optionally filtered
     * by status and a keyword matching the order id or customer contact details.
     */
    public PageResponse<OrderResponse> listForStaff(OrderStatus status, String q, Pageable pageable) {
        String query = q == null ? "" : q.trim();
        Page<Order> page = orderRepository.searchForStaff(query, status, pageable);
        return PageResponse.from(page, toResponses(page.getContent()));
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

    /**
     * Maps orders with one batched query per related table, so a page of
     * orders costs a constant number of queries instead of one per row.
     */
    private List<OrderResponse> toResponses(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = orders.stream().map(Order::getOrderId).toList();
        Map<String, List<OrderItem>> itemsByOrder = orderItemRepository
                .findByOrder_OrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(item -> item.getOrder().getOrderId()));
        Set<String> variantIds = itemsByOrder.values().stream()
                .flatMap(List::stream)
                .map(item -> item.getVariant().getVariantId())
                .collect(Collectors.toSet());

        Map<String, List<String>> attributesByVariant = variantIds.isEmpty()
                ? Map.of()
                : variantAttributeValueRepository.findByVariant_VariantIdIn(variantIds)
                        .stream()
                        .collect(Collectors.groupingBy(
                                value -> value.getVariant().getVariantId(),
                                Collectors.mapping(
                                        value -> value.getAttributeValue().getValue(),
                                        Collectors.toList())));
        Map<String, String> thumbnailByVariant = variantIds.isEmpty()
                ? Map.of()
                : mediaRepository.findByVariant_VariantIdIn(variantIds).stream()
                        .filter(media -> media.getUrl() != null && !media.getUrl().isBlank())
                        .collect(Collectors.toMap(
                                media -> media.getVariant().getVariantId(),
                                Media::getUrl,
                                (first, second) -> first));
        Set<String> variantsWithoutThumbnail = variantIds.stream()
                .filter(variantId -> !thumbnailByVariant.containsKey(variantId))
                .collect(Collectors.toSet());
        if (!variantsWithoutThumbnail.isEmpty()) {
            Map<String, String> productIdByVariant = itemsByOrder.values().stream()
                    .flatMap(List::stream)
                    .filter(item -> variantsWithoutThumbnail.contains(
                            item.getVariant().getVariantId()))
                    .collect(Collectors.toMap(
                            item -> item.getVariant().getVariantId(),
                            item -> item.getVariant().getProduct().getProductId(),
                            (first, second) -> first));
            Map<String, String> productThumbnails =
                    productThumbnails(Set.copyOf(productIdByVariant.values()));
            productIdByVariant.forEach((variantId, productId) -> {
                String url = productThumbnails.get(productId);
                if (url != null) {
                    thumbnailByVariant.put(variantId, url);
                }
            });
        }
        Map<String, PaymentResponse> paymentsByOrder = paymentRepository
                .findByOrder_OrderIdIn(orderIds).stream()
                .collect(Collectors.toMap(
                        payment -> payment.getOrder().getOrderId(),
                        paymentMapper::toPaymentResponse,
                        (first, second) -> first));

        return orders.stream()
                .map(order -> {
                    List<OrderItemResponse> items = itemsByOrder
                            .getOrDefault(order.getOrderId(), List.of()).stream()
                            .map(item -> {
                                String variantId = item.getVariant().getVariantId();
                                return orderMapper.toOrderItemResponse(item,
                                        thumbnailByVariant.get(variantId),
                                        attributesByVariant.getOrDefault(variantId, List.of()));
                            })
                            .toList();
                    return orderMapper.toOrderResponse(order, items,
                            paymentsByOrder.get(order.getOrderId()));
                })
                .toList();
    }

    /**
     * Product-level image fallback, matching the public product-media endpoint:
     * direct product media first, then media attached to a sibling variant.
     */
    private Map<String, String> productThumbnails(Set<String> productIds) {
        Map<String, String> urls = mediaRepository.findByProduct_ProductIdIn(productIds).stream()
                .filter(media -> media.getUrl() != null && !media.getUrl().isBlank())
                .collect(Collectors.toMap(
                        media -> media.getProduct().getProductId(),
                        Media::getUrl,
                        (first, second) -> first));
        Set<String> missing = productIds.stream()
                .filter(productId -> !urls.containsKey(productId))
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            mediaRepository.findByVariant_Product_ProductIdIn(missing).stream()
                    .filter(media -> media.getUrl() != null && !media.getUrl().isBlank())
                    .forEach(media -> urls.putIfAbsent(
                            media.getVariant().getProduct().getProductId(), media.getUrl()));
        }
        return urls;
    }

    private OrderResponse toResponse(Order order) {
        return toResponses(List.of(order)).get(0);
    }
}
