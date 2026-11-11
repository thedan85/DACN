package com.project.dacn.service.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.order.ShipOrderRequest;
import com.project.dacn.dto.request.order.StaffOrderStatusUpdateRequest;
import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.shipping.ghn.GhnClient;
import com.project.dacn.integration.shipping.ghn.dto.GhnCreateOrderRequest;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private static final int MAX_ORDER_SEARCH_LENGTH = 100;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;
    private final VariantRepository variantRepository;
    private final VariantAttributeValueRepository variantAttributeValueRepository;
    private final MediaRepository mediaRepository;
    private final PaymentRepository paymentRepository;
    private final GhnClient ghnClient;

    public PageResponse<OrderResponse> list(Long customerId, OrderStatus status, String q,
                                            Instant createdAfter, Pageable pageable) {
        String query = q == null ? "" : q.trim();
        if (query.length() > MAX_ORDER_SEARCH_LENGTH) {
            throw new IllegalArgumentException(
                    "order search query must be at most " + MAX_ORDER_SEARCH_LENGTH + " characters");
        }
        Page<Order> page = orderRepository.searchForCustomer(
                customerId, query, status, createdAfter, pageable);
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

    // shortcut: the GHN call holds the order lock for at most the read timeout; move it outside the transaction if ship traffic grows.
    @Transactional
    public OrderResponse shipOrder(String orderId, ShipOrderRequest request) {
        Order order = orderRepository.findWithLockByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("order not found: " + orderId));
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException("order is not confirmed: " + order.getStatus());
        }
        if (order.getGhnOrderCode() != null) {
            throw new InvalidOrderStateException("order already has GHN order: " + order.getGhnOrderCode());
        }
        mergeShipAddress(order, request);
        if (!StringUtils.hasText(order.getShippingProvince())
                || !StringUtils.hasText(order.getShippingWard())) {
            throw new InvalidOrderStateException("order has no GHN address");
        }
        Payment payment = requireShippablePayment(orderId);
        List<OrderItem> items = orderItemRepository.findByOrder_OrderId(orderId);
        int units = items.stream().mapToInt(OrderItem::getQuantity).sum();
        if (units * 500 >= 20_000) {
            throw new InvalidOrderStateException("order exceeds GHN 20 kg limit");
        }
        GhnCreateOrderRequest ghnRequest = buildGhnRequest(
                order, payment, items, Math.max(500, units * 500));
        GhnClient.CreatedOrder created = ghnClient.createOrder(ghnRequest);
        order.setGhnOrderCode(created.orderCode());
        return toResponse(order);
    }

    private Payment requireShippablePayment(String orderId) {
        Payment payment = paymentRepository.findByOrder_OrderId(orderId).orElse(null);
        if (payment == null) {
            throw new InvalidOrderStateException("order is not paid: no payment");
        }
        if (!isShippable(payment)) {
            throw new InvalidOrderStateException("order is not paid: " + payment.getStatus());
        }
        return payment;
    }

    private boolean isShippable(Payment payment) {
        return payment != null
                && (payment.getMethod() == PaymentMethod.COD
                        || payment.getStatus() == PaymentStatus.CONFIRMED);
    }

    private void mergeShipAddress(Order order, ShipOrderRequest request) {
        if (request == null) {
            return;
        }
        if (StringUtils.hasText(request.address())) {
            order.setAddress(request.address().trim());
        }
        if (StringUtils.hasText(request.province())) {
            order.setShippingProvince(request.province().trim());
        }
        if (StringUtils.hasText(request.ward())) {
            order.setShippingWard(request.ward().trim());
        }
    }

    private int vnd(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new InvalidOrderStateException("invalid VND amount: " + amount);
        }
        try {
            return amount.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException ex) {
            throw new InvalidOrderStateException(amount + " is out of range");
        }
    }

    private GhnCreateOrderRequest buildGhnRequest(Order order, Payment payment,
            List<OrderItem> items, int weight) {
        // shortcut: fixed parcel size and 500 g per unit; add per-variant dimensions when the catalog supports them.
        List<GhnCreateOrderRequest.Item> ghnItems = items.stream()
                .map(item -> new GhnCreateOrderRequest.Item(
                        item.getVariant().getProduct().getName(),
                        item.getVariant().getVariantId(),
                        item.getQuantity(),
                        vnd(item.getUnitPrice())))
                .toList();
        return new GhnCreateOrderRequest(
                1,
                "CHOXEMHANGKHONGTHU",
                order.getOrderId(),
                order.getRecipientName(),
                order.getPhone(),
                order.getAddress(),
                order.getShippingWard(),
                order.getShippingProvince(),
                true,
                content(items),
                weight,
                20,
                20,
                10,
                2,
                payment.getMethod() == PaymentMethod.COD ? vnd(order.getTotal()) : 0,
                ghnItems);
    }

    private String content(List<OrderItem> items) {
        return items.stream()
                .map(item -> item.getVariant().getProduct().getName() + " x" + item.getQuantity())
                .collect(Collectors.joining(", "));
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
        Set<String> productIds = itemsByOrder.values().stream()
                .flatMap(List::stream)
                .map(item -> item.getVariant().getProduct().getProductId())
                .collect(Collectors.toSet());
        Map<String, String> variantThumbnails = new HashMap<>();
        Map<String, String> productThumbnails = new HashMap<>();
        List<Media> media = variantIds.isEmpty()
                ? List.of()
                : mediaRepository.findForOrderItems(variantIds, productIds);
        // Variant media wins for the variant; direct product media wins over a
        // sibling variant's image as the product-level fallback.
        for (Media item : media) {
            String url = item.getUrl();
            if (url == null || url.isBlank()) {
                continue;
            }
            if (item.getVariant() != null) {
                variantThumbnails.putIfAbsent(item.getVariant().getVariantId(), url);
            }
            if (item.getProduct() != null) {
                productThumbnails.putIfAbsent(item.getProduct().getProductId(), url);
            }
        }
        for (Media item : media) {
            if (item.getVariant() == null || item.getVariant().getProduct() == null) {
                continue;
            }
            String url = item.getUrl();
            if (url != null && !url.isBlank()) {
                productThumbnails.putIfAbsent(
                        item.getVariant().getProduct().getProductId(), url);
            }
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
                                String thumbnail = variantThumbnails.get(variantId);
                                if (thumbnail == null) {
                                    thumbnail = productThumbnails.get(
                                            item.getVariant().getProduct().getProductId());
                                }
                                return orderMapper.toOrderItemResponse(item,
                                        thumbnail,
                                        attributesByVariant.getOrDefault(variantId, List.of()));
                            })
                            .toList();
                    return orderMapper.toOrderResponse(order, items,
                            paymentsByOrder.get(order.getOrderId()));
                })
                .toList();
    }

    private OrderResponse toResponse(Order order) {
        return toResponses(List.of(order)).get(0);
    }
}
