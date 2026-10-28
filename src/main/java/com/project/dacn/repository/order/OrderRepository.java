package com.project.dacn.repository.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.entity.order.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderId(String orderId);

    Optional<Order> findByOrderIdAndCustomer_Id(String orderId, Long customerId);

    List<Order> findByCustomer_IdOrderByIdDesc(Long customerId);

    @Query("""
            select o from Order o
            where (:status is null or o.status = :status)
              and (:q = ''
                or lower(o.orderId) like lower(concat('%', :q, '%'))
                or lower(o.recipientName) like lower(concat('%', :q, '%'))
                or lower(o.phone) like lower(concat('%', :q, '%'))
                or lower(o.customer.name) like lower(concat('%', :q, '%'))
                or lower(o.customer.phone) like lower(concat('%', :q, '%'))
                or lower(o.customer.user.email) like lower(concat('%', :q, '%')))
            order by o.id desc
            """)
    Page<Order> searchForStaff(@Param("q") String q,
                               @Param("status") OrderStatus status,
                               Pageable pageable);

    /**
     * Serializes staff decisions with payment settlement: both lock the same
     * order row, so an order cannot be cancelled and paid at the same time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.orderId = :orderId")
    Optional<Order> findWithLockByOrderId(@Param("orderId") String orderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Order o set o.status = :next where o.orderId = :orderId and o.status = :current")
    int updateStatus(@Param("orderId") String orderId,
                     @Param("current") OrderStatus current,
                     @Param("next") OrderStatus next);
}
