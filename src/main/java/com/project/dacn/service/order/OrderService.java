package com.project.dacn.service.order;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.order.Order;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper orderMapper;

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

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = orderItemRepository
                .findByOrder_OrderId(order.getOrderId()).stream()
                .map(orderMapper::toOrderItemResponse)
                .toList();
        return orderMapper.toOrderResponse(order, items);
    }
}
