package com.project.dacn.repository.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.entity.order.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderId(String orderId);

    Optional<Order> findByOrderIdAndCustomer_Id(String orderId, Long customerId);

    List<Order> findByCustomer_IdOrderByIdDesc(Long customerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Order o set o.status = :next where o.orderId = :orderId and o.status = :current")
    int updateStatus(@Param("orderId") String orderId,
                     @Param("current") OrderStatus current,
                     @Param("next") OrderStatus next);
}
