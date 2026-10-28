package com.project.dacn.repository.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.entity.order.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @EntityGraph(attributePaths = {"variant", "order"})
    List<OrderItem> findByOrder_OrderId(String orderId);

    Optional<OrderItem> findFirstByVariant_VariantIdAndOrder_Customer_IdAndOrder_StatusOrderByCreatedAtDesc(
            String variantId, Long customerId, OrderStatus status);

    @EntityGraph(attributePaths = {"variant", "order"})
    List<OrderItem> findByOrder_OrderIdIn(Collection<String> orderIds);
}
