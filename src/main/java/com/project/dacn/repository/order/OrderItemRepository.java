package com.project.dacn.repository.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.entity.order.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @EntityGraph(attributePaths = {"variant", "order"})
    List<OrderItem> findByOrder_OrderId(String orderId);

    interface StockReleaseTarget {
        Long getVariantId();

        Long getQuantity();
    }

    /**
     * Native release projection: the entity query hides soft-deleted variants,
     * so a hidden historical target would otherwise be silently skipped.
     */
    @Query(value = """
            select oi.variant_id as "variantId", sum(oi.quantity) as "quantity"
              from order_items oi
              join orders o on o.id = oi.order_id
             where o.order_id = :orderId
             group by oi.variant_id
             order by oi.variant_id asc
            """, nativeQuery = true)
    List<StockReleaseTarget> findStockReleaseTargetsByOrderId(
            @Param("orderId") String orderId);

    Optional<OrderItem> findFirstByVariant_VariantIdAndOrder_Customer_IdAndOrder_StatusOrderByCreatedAtDesc(
            String variantId, Long customerId, OrderStatus status);

    @EntityGraph(attributePaths = {"variant", "variant.product", "order",
            "variant.attributeValues", "variant.attributeValues.attributeValue"})
    List<OrderItem> findByOrder_OrderIdIn(Collection<String> orderIds);
}
