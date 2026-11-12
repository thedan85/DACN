package com.project.dacn.repository.payment;

import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.payment.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPaymentId(String paymentId);

    Optional<Payment> findByOrder_OrderId(String orderId);

    @EntityGraph(attributePaths = "order")
    List<Payment> findByOrder_OrderIdIn(Collection<String> orderIds);

    /** Payment and its order in one query, scoped to the owning customer. */
    @EntityGraph(attributePaths = "order")
    @Query("select p from Payment p where p.order.orderId = :orderId "
            + "and p.order.customer.id = :customerId")
    Optional<Payment> findWithOrderByOrderIdAndCustomerId(
            @Param("orderId") String orderId,
            @Param("customerId") Long customerId);

    Optional<Payment> findByTransactionId(String transactionId);

    /** Serializes lifecycle transitions with payment settlement on the payment row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.order.orderId = :orderId")
    Optional<Payment> findWithLockByOrder_OrderId(@Param("orderId") String orderId);

    /**
     * Settles a payment only while it is still in {@code current}. Two IPNs
     * arriving at once both issue this UPDATE; the loser blocks on the row
     * lock, re-checks the predicate and updates 0 rows.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Payment p set p.status = :next, p.transactionId = :transactionId, "
            + "p.paidAt = :paidAt where p.paymentId = :paymentId and p.status = :current")
    int settle(@Param("paymentId") String paymentId,
               @Param("current") PaymentStatus current,
               @Param("next") PaymentStatus next,
               @Param("transactionId") String transactionId,
               @Param("paidAt") Instant paidAt);
}
