package com.project.dacn.service.order;

import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds order responses with one batched query per related table, so a page
 * of orders costs a constant number of queries instead of one per row.
 */
@Component
@RequiredArgsConstructor
public class OrderResponseAssembler {

    private final OrderItemRepository orderItemRepository;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;
    private final MediaRepository mediaRepository;

    public List<OrderResponse> toResponses(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = orders.stream().map(Order::getOrderId).toList();
        List<OrderItem> orderItems = orderItemRepository.findByOrder_OrderIdIn(orderIds);
        Map<String, List<OrderItem>> itemsByOrder = orderItems.stream()
                .collect(Collectors.groupingBy(item -> item.getOrder().getOrderId()));
        Set<String> variantIds = new HashSet<>();
        Set<String> productIds = new HashSet<>();
        Map<String, List<String>> attributesByVariant = new HashMap<>();
        for (OrderItem item : orderItems) {
            Variant variant = item.getVariant();
            variantIds.add(variant.getVariantId());
            productIds.add(variant.getProduct().getProductId());
            attributesByVariant.computeIfAbsent(variant.getVariantId(), id ->
                    variant.getAttributeValues().stream()
                            .map(value -> value.getAttributeValue().getValue())
                            .filter(Objects::nonNull)
                            .toList());
        }
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
        Map<String, PaymentResponse> paymentsByOrder = orders.stream()
                .filter(order -> order.getPayment() != null)
                .collect(Collectors.toMap(
                        Order::getOrderId,
                        order -> paymentMapper.toPaymentResponse(order.getPayment()),
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

    public OrderResponse toResponse(Order order) {
        return toResponses(List.of(order)).get(0);
    }
}
