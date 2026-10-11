package com.project.dacn.modules.order.repository;

import com.project.dacn.modules.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    Optional<OrderItem> findByOrderItemId(String orderItemId);

    List<OrderItem> findByOrder_OrderId(String orderId);
}
