package com.project.dacn.mapper.payment;

import com.project.dacn.dto.request.payment.PaymentCreateRequest;
import com.project.dacn.dto.request.payment.PaymentUpdateRequest;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.entity.payment.Payment;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "paymentId", ignore = true)
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "paidAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Payment toPayment(PaymentCreateRequest request);

    @Mapping(source = "order.orderId", target = "orderId")
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
