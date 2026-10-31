package com.project.dacn.mapper.order;

import com.project.dacn.dto.request.order.OrderCreateRequest;
import com.project.dacn.dto.request.order.OrderItemCreateRequest;
import com.project.dacn.dto.request.order.OrderUpdateRequest;
import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "orderId", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Order toOrder(OrderCreateRequest request);

    @Mapping(target = "items", ignore = true)
    @Mapping(target = "payment", ignore = true)
    @Mapping(source = "customer.customerId", target = "customerId")
    @Mapping(source = "customer.name", target = "customerName")
    OrderResponse toOrderResponse(Order order);

    default OrderResponse toOrderResponse(Order order, List<OrderItemResponse> items) {
        return toOrderResponse(order, items, null);
    }

    default OrderResponse toOrderResponse(Order order, List<OrderItemResponse> items,
            PaymentResponse payment) {
        OrderResponse base = toOrderResponse(order);
        return new OrderResponse(base.orderId(), base.customerId(), base.customerName(),
                base.status(), base.total(), base.address(), base.recipientName(),
                base.phone(), items == null ? List.of() : items,
                base.createdAt(), base.updatedAt(), payment);
    }

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "orderId", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "total", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateOrder(OrderUpdateRequest request, @MappingTarget Order order);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "variant", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    OrderItem toOrderItem(OrderItemCreateRequest request);

    @Mapping(source = "order.orderId", target = "orderId")
    @Mapping(source = "variant.variantId", target = "variantId")
    @Mapping(source = "variant.sku", target = "sku")
    @Mapping(source = "variant.product.name", target = "productName")
    @Mapping(target = "thumbnail", ignore = true)
    @Mapping(target = "attributes", ignore = true)
    OrderItemResponse toOrderItemResponse(OrderItem item);

    default OrderItemResponse toOrderItemResponse(OrderItem item, String thumbnail,
            List<String> attributes) {
        OrderItemResponse base = toOrderItemResponse(item);
        return new OrderItemResponse(base.orderId(), base.variantId(), base.sku(),
                base.quantity(), base.unitPrice(), base.createdAt(), base.productName(),
                thumbnail, attributes == null ? List.of() : attributes);
    }

}
