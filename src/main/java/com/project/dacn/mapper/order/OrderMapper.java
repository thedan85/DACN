package com.project.dacn.mapper.order;

import com.project.dacn.dto.request.order.OrderCreateRequest;
import com.project.dacn.dto.request.order.OrderItemCreateRequest;
import com.project.dacn.dto.request.order.OrderUpdateRequest;
import com.project.dacn.dto.request.order.PaymentCreateRequest;
import com.project.dacn.dto.request.order.PaymentUpdateRequest;
import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.dto.response.order.PaymentResponse;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.order.Payment;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "orderId", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Order toOrder(OrderCreateRequest request);

    @Mapping(source = "customer.customerId", target = "customerId")
    @Mapping(source = "customer.name", target = "customerName")
    OrderResponse toOrderResponse(Order order);

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

    @Mapping(source = "id", target = "orderItemId")
    @Mapping(source = "order.orderId", target = "orderId")
    @Mapping(source = "variant.variantId", target = "variantId")
    @Mapping(source = "variant.sku", target = "sku")
    OrderItemResponse toOrderItemResponse(OrderItem item);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "paymentId", ignore = true)
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "paidAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Payment toPayment(PaymentCreateRequest request);

    PaymentResponse toPaymentResponse(Payment payment);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "paymentId", ignore = true)
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "method", ignore = true)
    @Mapping(target = "amount", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updatePayment(PaymentUpdateRequest request, @MappingTarget Payment payment);
}
