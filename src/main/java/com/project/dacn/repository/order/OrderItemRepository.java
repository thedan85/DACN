package com.project.dacn.repository.order;

import com.project.dacn.entity.order.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @EntityGraph(attributePaths = {"variant", "order"})
    List<OrderItem> findByOrder_OrderId(String orderId);

    @EntityGraph(attributePaths = {"variant", "order"})
    List<OrderItem> findByOrder_OrderIdIn(Collection<String> orderIds);
}
