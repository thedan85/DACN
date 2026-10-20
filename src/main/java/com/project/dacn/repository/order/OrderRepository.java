package com.project.dacn.repository.order;

import com.project.dacn.entity.order.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderId(String orderId);

    Optional<Order> findByOrderIdAndCustomer_Id(String orderId, Long customerId);

    List<Order> findByCustomer_IdOrderByIdDesc(Long customerId);
}
